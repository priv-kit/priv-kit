package priv.kit.core.internal.external

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import priv.kit.core.PrivilegeExternalStartup
import priv.kit.core.PrivilegeExternalStartupBridge
import priv.kit.core.PrivilegeExternalStartupBridgeOptions
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.StartupTranscript
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class PrivilegeExternalStartupBridgeRunner {
    fun run(
        commandLine: String,
        bridge: PrivilegeExternalStartupBridge,
        options: PrivilegeExternalStartupBridgeOptions,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        require(commandLine.isNotBlank()) { "commandLine must not be blank" }
        val forwardedListener = startupLogListener?.let { listener ->
            val receiver = PrivilegeExternalStartup.createReceiver(
                startupLogListener = listener,
                sourcePrefix = options.sourcePrefix,
            )
            PrivilegeStartupLogListener { line -> receiver.receive(line) }
        }
        val transcript = StartupTranscript(
            maxCapturedLines = options.maxCapturedLines,
            startupLogListener = forwardedListener,
        )
        val stdoutPipe = createPipe("stdout")
        val stderrPipe = try {
            createPipe("stderr")
        } catch (throwable: Throwable) {
            stdoutPipe.closeAll()
            throw throwable
        }
        val completionLatch = CountDownLatch(BRIDGE_COMPLETION_SIGNAL_COUNT)
        val resultRef = AtomicReference<ExternalStartupBridgeResult?>()
        val streamFailureRef = AtomicReference<Throwable?>()
        val resultReceiver = object : ResultReceiver(null) {
            override fun onReceiveResult(
                resultCode: Int,
                resultData: Bundle?,
            ) {
                if (
                    !resultRef.compareAndSet(
                        null,
                        ExternalStartupBridgeResult(resultCode, resultData ?: Bundle.EMPTY),
                    )
                ) return
                completionLatch.countDown()
            }
        }
        val readerThreads = listOf(
            consumePipe(
                descriptor = stdoutPipe.readEnd,
                source = STDOUT_SOURCE,
                transcript = transcript,
                completionLatch = completionLatch,
                streamFailureRef = streamFailureRef,
            ),
            consumePipe(
                descriptor = stderrPipe.readEnd,
                source = STDERR_SOURCE,
                transcript = transcript,
                completionLatch = completionLatch,
                streamFailureRef = streamFailureRef,
            ),
        )

        try {
            try {
                bridge.start(
                    commandLine = commandLine,
                    stdout = stdoutPipe.writeEnd,
                    stderr = stderrPipe.writeEnd,
                    resultReceiver = resultReceiver,
                )
            } catch (throwable: Throwable) {
                if (throwable is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (throwable is PrivilegeStartupException) throw throwable
                throw PrivilegeStartupException("External startup bridge failed to start command", throwable)
            } finally {
                stdoutPipe.closeWriteEnd()
                stderrPipe.closeWriteEnd()
            }

            val completed = try {
                completionLatch.await(options.timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PrivilegeStartupException(
                    "Interrupted while waiting for external startup bridge result",
                    exception,
                )
            }
            if (!completed) {
                throw PrivilegeStartupException(
                    "External startup bridge timed out after ${options.timeoutMillis}ms: ${transcript.text()}",
                )
            }

            val result = resultRef.get()
                ?: throw PrivilegeStartupException("External startup bridge returned no result")
            if (result.resultCode != ExternalStartupBridgeProtocol.RESULT_SUCCESS) {
                throw result.toException(transcript.text())
            }
            streamFailureRef.get()?.let { throwable ->
                throw PrivilegeStartupException("Failed to read external startup bridge output", throwable)
            }
            return transcript.text()
        } finally {
            stdoutPipe.closeAll()
            stderrPipe.closeAll()
            joinReaders(readerThreads)
        }
    }

    private fun createPipe(source: String): ExternalStartupBridgePipe =
        try {
            ExternalStartupBridgePipe.create()
        } catch (exception: IOException) {
            throw PrivilegeStartupException("Failed to create external startup $source pipe", exception)
        }

    private fun consumePipe(
        descriptor: ParcelFileDescriptor,
        source: String,
        transcript: StartupTranscript,
        completionLatch: CountDownLatch,
        streamFailureRef: AtomicReference<Throwable?>,
    ): Thread =
        thread(name = "priv-kit-external-bridge-$source", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.forEach { message ->
                            transcript.append(source, message)
                        }
                    }
            } catch (throwable: Throwable) {
                streamFailureRef.compareAndSet(null, throwable)
            } finally {
                completionLatch.countDown()
            }
        }

    private fun joinReaders(readerThreads: List<Thread>) {
        readerThreads.forEach { reader ->
            try {
                reader.join(READER_JOIN_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private companion object {
        const val BRIDGE_COMPLETION_SIGNAL_COUNT = 3
        const val READER_JOIN_TIMEOUT_MILLIS = 500L
        const val STDOUT_SOURCE = "stdout"
        const val STDERR_SOURCE = "stderr"
    }
}

internal object ExternalStartupBridgeProtocol {
    const val RESULT_SUCCESS: Int = 0
    const val RESULT_FAILURE: Int = 1
    const val RESULT_BUSY: Int = 2
    const val RESULT_CLOSED: Int = 3
    const val RESULT_MESSAGE_KEY: String = "priv.kit.core.external.bridge.MESSAGE"
}

private data class ExternalStartupBridgeResult(
    val resultCode: Int,
    val resultData: Bundle,
) {
    fun toException(transcript: String): PrivilegeStartupException =
        PrivilegeStartupException(
            buildString {
                append(
                    resultData.getString(ExternalStartupBridgeProtocol.RESULT_MESSAGE_KEY)
                        ?.takeIf { it.isNotBlank() }
                        ?: "External startup bridge command failed",
                )
                append('\n')
                append(transcript)
            },
        )
}

private class ExternalStartupBridgePipe private constructor(
    val readEnd: ParcelFileDescriptor,
    val writeEnd: ParcelFileDescriptor,
) {
    fun closeWriteEnd() {
        runCatching { writeEnd.close() }
    }

    fun closeAll() {
        runCatching { readEnd.close() }
        closeWriteEnd()
    }

    companion object {
        fun create(): ExternalStartupBridgePipe {
            val descriptors = ParcelFileDescriptor.createPipe()
            return ExternalStartupBridgePipe(
                readEnd = descriptors[0],
                writeEnd = descriptors[1],
            )
        }
    }
}
