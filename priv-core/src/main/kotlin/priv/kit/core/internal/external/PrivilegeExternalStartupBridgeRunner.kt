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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeExternalStartupBridgeRunner {
    suspend fun run(
        commandLine: String,
        bridge: PrivilegeExternalStartupBridge,
        options: PrivilegeExternalStartupBridgeOptions,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String = withContext(Dispatchers.IO) {
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
        val result = CompletableDeferred<ExternalStartupBridgeResult>()
        val resultReceiver = object : ResultReceiver(null) {
            override fun onReceiveResult(
                resultCode: Int,
                resultData: Bundle?,
            ) {
                result.complete(
                    ExternalStartupBridgeResult(resultCode, resultData ?: Bundle.EMPTY),
                )
            }
        }
        val readers = listOf(
            async {
                consumePipe(
                    descriptor = stdoutPipe.readEnd,
                    source = STDOUT_SOURCE,
                    transcript = transcript,
                )
            },
            async {
                consumePipe(
                    descriptor = stderrPipe.readEnd,
                    source = STDERR_SOURCE,
                    transcript = transcript,
                )
            },
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
                if (throwable is CancellationException) throw throwable
                if (throwable is PrivilegeStartupException) throw throwable
                throw PrivilegeStartupException("External startup bridge failed to start command", throwable)
            } finally {
                stdoutPipe.closeWriteEnd()
                stderrPipe.closeWriteEnd()
            }

            val completion = try {
                withTimeout(options.timeoutMillis.milliseconds) {
                    result.await() to readers.awaitAll().firstOrNull { it != null }
                }
            } catch (exception: kotlinx.coroutines.TimeoutCancellationException) {
                throw PrivilegeStartupException(
                    "External startup bridge timed out after ${options.timeoutMillis}ms: ${transcript.text()}",
                    exception,
                )
            }

            val bridgeResult = completion.first
            if (bridgeResult.resultCode != ExternalStartupBridgeProtocol.RESULT_SUCCESS) {
                throw bridgeResult.toException(transcript.text())
            }
            completion.second?.let { throwable ->
                throw PrivilegeStartupException("Failed to read external startup bridge output", throwable)
            }
            return@withContext transcript.text()
        } finally {
            stdoutPipe.closeAll()
            stderrPipe.closeAll()
            readers.forEach { it.cancel() }
            withContext(NonCancellable) {
                withTimeoutOrNull(READER_JOIN_TIMEOUT_MILLIS.milliseconds) {
                    readers.joinAll()
                }
            }
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
    ): Throwable? =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.forEach { message ->
                        transcript.append(source, message)
                    }
                }
            null
        } catch (throwable: Throwable) {
            throwable
        }

    private companion object {
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
