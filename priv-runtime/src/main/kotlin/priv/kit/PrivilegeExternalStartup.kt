package priv.kit

import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

public data class PrivilegeExternalStartupOptions @JvmOverloads public constructor(
    public val shellPath: String = DEFAULT_SHELL_PATH,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxCapturedLines: Int = DEFAULT_MAX_CAPTURED_LINES,
) {
    init {
        require(shellPath.isNotBlank()) { "shellPath must not be blank" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(maxCapturedLines > 0) { "maxCapturedLines must be positive" }
    }

    public companion object {
        public const val DEFAULT_SHELL_PATH: String = "/system/bin/sh"
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 2_000L
        public const val DEFAULT_MAX_CAPTURED_LINES: Int = 80
    }
}

public data class PrivilegeExternalStartupResult public constructor(
    public val exitCode: Int,
    public val output: String,
)

public object PrivilegeExternalStartup {
    @JvmStatic
    @JvmOverloads
    @Throws(PrivilegeStartupException::class)
    public fun runInCurrentProcess(
        commandLine: String,
        options: PrivilegeExternalStartupOptions = PrivilegeExternalStartupOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeExternalStartupResult =
        PrivilegeExternalStartupProcessRunner().run(
            commandLine = commandLine,
            options = options,
            startupLogListener = startupLogListener,
        )

    @JvmStatic
    @JvmOverloads
    public fun createReceiver(
        startupLogListener: PrivilegeStartupLogListener,
        sourcePrefix: String? = null,
    ): PrivilegeExternalStartupReceiver =
        PrivilegeExternalStartupReceiver(
            startupLogListener = startupLogListener,
            sourcePrefix = sourcePrefix,
        )
}

public class PrivilegeExternalStartupReceiver @JvmOverloads public constructor(
    private val startupLogListener: PrivilegeStartupLogListener,
    sourcePrefix: String? = null,
) {
    private val prefix = sourcePrefix.toStartupLogSourceOrNull()
    private val adapter = PrivilegeStartupLogListener { line ->
        receive(line)
    }

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
        if (!line.isPrivKitInternalMetadata) {
            startupLogListener.onLog(
                line.copy(source = applyPrefix(line.source)),
            )
        }
    }

    public fun asStartupLogListener(): PrivilegeStartupLogListener = adapter

    private fun applyPrefix(source: String): String =
        prefix?.let { "$it/$source" } ?: source

    private companion object {
        const val DEFAULT_REMOTE_SOURCE = "external"
    }
}

internal class PrivilegeExternalStartupProcessRunner(
    private val processFactory: (PrivilegeExternalStartupOptions, String) -> Process = { options, commandLine ->
        ProcessBuilder(options.shellPath, "-c", commandLine).start()
    },
) {
    @Throws(PrivilegeStartupException::class)
    fun run(
        commandLine: String,
        options: PrivilegeExternalStartupOptions,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeExternalStartupResult {
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

        val readerThreads = listOf(
            consume(process.inputStream, "stdout", transcript),
            consume(process.errorStream, "stderr", transcript),
        )

        val completed = try {
            process.waitFor(options.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            transcript.append("diag", "Interrupted while waiting for shell start command")
            throw PrivilegeStartupException(
                "Interrupted while waiting for external startup command: ${transcript.text()}",
                exception,
            )
        }

        if (!completed) {
            process.destroy()
            transcript.append(
                "diag",
                "Shell start command did not return after ${options.timeoutMillis}ms",
            )
            joinReaders(readerThreads, timeoutMillis = READER_JOIN_TIMEOUT_MILLIS)
            throw PrivilegeStartupException(
                "External startup command timed out: ${transcript.text()}",
            )
        }

        joinReaders(readerThreads, timeoutMillis = READER_JOIN_TIMEOUT_MILLIS)
        val exitCode = process.exitValue()
        transcript.append("diag", "Shell start command exited code=$exitCode")
        if (exitCode != 0) {
            throw PrivilegeStartupException(
                "External startup command exited code=$exitCode: ${transcript.text()}",
            )
        }
        return PrivilegeExternalStartupResult(
            exitCode = exitCode,
            output = transcript.text(),
        )
    }

    private fun consume(
        inputStream: InputStream,
        source: String,
        transcript: StartupTranscript,
    ): Thread =
        thread(name = "priv-kit-external-start-$source", isDaemon = true) {
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

    private fun joinReaders(
        readerThreads: List<Thread>,
        timeoutMillis: Long,
    ) {
        readerThreads.forEach { reader ->
            try {
                reader.join(timeoutMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private companion object {
        const val READER_JOIN_TIMEOUT_MILLIS = 500L
    }
}

private class StartupTranscript(
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
            if (output.size < maxCapturedLines && !line.isPrivKitInternalMetadata) {
                output += "[${line.source}] ${line.message}"
            }
        }
        if (!line.isPrivKitInternalMetadata) {
            runCatching {
                startupLogListener?.onLog(line)
            }
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
