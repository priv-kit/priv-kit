package priv.kit.core

import priv.kit.core.internal.external.PrivilegeExternalStartupBridgeRunner
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

public data class PrivilegeExternalStartupOptions public constructor(
    public val shellPath: String = DEFAULT_SHELL_PATH,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxCapturedLines: Int = DEFAULT_MAX_CAPTURED_LINES,
) {
    init {
        require(shellPath.isNotBlank()) { "shellPath must not be blank" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(maxCapturedLines > 0) { "maxCapturedLines must be positive" }
    }

    internal companion object {
        internal const val DEFAULT_SHELL_PATH: String = "/system/bin/sh"
        internal const val DEFAULT_TIMEOUT_MILLIS: Long = 2_000L
        internal const val DEFAULT_MAX_CAPTURED_LINES: Int = 80
    }
}

public object PrivilegeExternalStartup {
    public suspend fun runInCurrentProcess(
        commandLine: String,
        options: PrivilegeExternalStartupOptions = PrivilegeExternalStartupOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): String = withContext(Dispatchers.IO) {
        PrivilegeExternalStartupProcessRunner().run(
            commandLine = commandLine,
            options = options,
            startupLogListener = startupLogListener,
        )
    }

    public fun createReceiver(
        startupLogListener: PrivilegeStartupLogListener,
        sourcePrefix: String? = null,
    ): PrivilegeExternalStartupReceiver =
        PrivilegeExternalStartupReceiver.create(
            startupLogListener = startupLogListener,
            sourcePrefix = sourcePrefix,
        )

    public suspend fun runThroughBridge(
        commandLine: String,
        bridge: PrivilegeExternalStartupBridge,
        options: PrivilegeExternalStartupBridgeOptions = PrivilegeExternalStartupBridgeOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): String =
        PrivilegeExternalStartupBridgeRunner().run(
            commandLine = commandLine,
            bridge = bridge,
            options = options,
            startupLogListener = startupLogListener,
        )

    public suspend fun runThroughBridge(
        commandLine: String,
        bridge: PrivilegeExternalStartupBridge,
        startupLogListener: PrivilegeStartupLogListener,
    ): String =
        runThroughBridge(
            commandLine = commandLine,
            bridge = bridge,
            options = PrivilegeExternalStartupBridgeOptions(),
            startupLogListener = startupLogListener,
        )
}

public class PrivilegeExternalStartupReceiver private constructor(
    private val startupLogListener: PrivilegeStartupLogListener,
    sourcePrefix: String? = null,
) {
    private val prefix = sourcePrefix.toStartupLogSourceOrNull()

    public fun receive(
        source: String?,
        message: String?,
    ) {
        if (message == null) return
        receive(
            PrivilegeStartupLogLine(
                source = source.toStartupLogSource(DEFAULT_REMOTE_SOURCE),
                message = message,
            ),
        )
    }

    public fun receive(line: PrivilegeStartupLogLine) {
        startupLogListener.onLog(
            line.copy(source = applyPrefix(line.source)),
        )
    }

    private fun applyPrefix(source: String): String =
        prefix?.let { "$it/$source" } ?: source

    internal companion object {
        private const val DEFAULT_REMOTE_SOURCE = "external"

        @JvmSynthetic
        fun create(
            startupLogListener: PrivilegeStartupLogListener,
            sourcePrefix: String?,
        ): PrivilegeExternalStartupReceiver =
            PrivilegeExternalStartupReceiver(
                startupLogListener = startupLogListener,
                sourcePrefix = sourcePrefix,
            )
    }
}

internal class PrivilegeExternalStartupProcessRunner(
    private val processFactory: (PrivilegeExternalStartupOptions, String) -> Process = { options, commandLine ->
        ProcessBuilder(options.shellPath, "-c", commandLine).start()
    },
) {
    suspend fun run(
        commandLine: String,
        options: PrivilegeExternalStartupOptions,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String = withContext(Dispatchers.IO) {
        require(commandLine.isNotBlank()) { "commandLine must not be blank" }
        val transcript = StartupTranscript(
            maxCapturedLines = options.maxCapturedLines,
            startupLogListener = startupLogListener,
        )
        transcript.append(
            "diag",
            "Starting Priv Kit shell start command${currentUidSuffix()}, length=${commandLine.length}",
        )

        val process = try {
            processFactory(options, commandLine)
        } catch (throwable: Throwable) {
            transcript.append("diag", throwable.toOutputLine("Failed to start command"))
            throw PrivilegeStartupException(
                "Failed to start external startup command: ${transcript.text()}",
                throwable,
            )
        }
        val streams = listOf(process.inputStream, process.errorStream)

        supervisorScope {
            val readers = listOf(
                async(CoroutineName("priv-kit-external-start-stdout")) {
                    runInterruptible { consume(streams[0], "stdout", transcript) }
                },
                async(CoroutineName("priv-kit-external-start-stderr")) {
                    runInterruptible { consume(streams[1], "stderr", transcript) }
                },
            )

            val completed = try {
                runInterruptible {
                    process.waitFor(options.timeoutMillis, TimeUnit.MILLISECONDS)
                }
            } catch (exception: CancellationException) {
                closeProcess(process, streams)
                throw exception
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                closeProcess(process, streams)
                transcript.append("diag", "Interrupted while waiting for shell start command")
                throw PrivilegeStartupException(
                    "Interrupted while waiting for external startup command: ${transcript.text()}",
                    exception,
                )
            }

            if (!completed) {
                closeProcess(process, streams)
                transcript.append(
                    "diag",
                    "Shell start command did not return after ${options.timeoutMillis}ms",
                )
                awaitReaders(streams, readers)
                throw PrivilegeStartupException(
                    "External startup command timed out: ${transcript.text()}",
                )
            }

            awaitReaders(streams, readers)
            val exitCode = process.exitValue()
            transcript.append("diag", "Shell start command exited code=$exitCode")
            if (exitCode != 0) {
                throw PrivilegeStartupException(
                    "External startup command exited code=$exitCode: ${transcript.text()}",
                )
            }
            transcript.text()
        }
    }

    private fun consume(
        inputStream: InputStream,
        source: String,
        transcript: StartupTranscript,
    ) {
        runCatching {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    transcript.append(source, line)
                }
            }
        }.onFailure { throwable ->
            transcript.append(source, throwable.toOutputLine("Failed to read output"))
        }
    }

    private suspend fun awaitReaders(
        streams: List<InputStream>,
        readers: List<Deferred<Unit>>,
    ) {
        if (withTimeoutOrNull(READER_JOIN_TIMEOUT_MILLIS) { readers.awaitAll() } == null) {
            streams.forEach { runCatching(it::close) }
            readers.forEach { it.cancel() }
            readers.joinAll()
        }
    }

    private fun closeProcess(
        process: Process,
        streams: List<InputStream>,
    ) {
        process.destroy()
        streams.forEach { runCatching(it::close) }
    }

    private companion object {
        const val READER_JOIN_TIMEOUT_MILLIS = 500L
    }
}

internal class StartupTranscript(
    private val maxCapturedLines: Int,
    private val startupLogListener: PrivilegeStartupLogListener?,
) {
    private val output = Collections.synchronizedList(mutableListOf<String>())

    fun append(
        source: String,
        message: String,
    ) {
        val line = PrivilegeStartupLogLine(source = source, message = message)
        synchronized(output) {
            if (output.size < maxCapturedLines) {
                output += "[${line.source}] ${line.message}"
            }
        }
        runCatching {
            startupLogListener?.onLog(line)
        }
    }

    fun text(): String =
        synchronized(output) {
            output.joinToString("\n").ifBlank { "<no output>" }
        }
}

private fun Throwable.toOutputLine(prefix: String): String =
    "$prefix: ${javaClass.simpleName}: ${message.orEmpty()}"

private fun currentUidSuffix(): String =
    runCatching {
        " uid=${android.os.Process.myUid()}"
    }.getOrDefault("")

private fun String?.toStartupLogSource(defaultSource: String): String =
    toStartupLogSourceOrNull() ?: defaultSource

private fun String?.toStartupLogSourceOrNull(): String? =
    this
        ?.replace('\u0000', ' ')
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.trim()
        ?.ifBlank { null }
