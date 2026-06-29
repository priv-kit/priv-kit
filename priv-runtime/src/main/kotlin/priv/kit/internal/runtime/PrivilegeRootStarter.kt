package priv.kit.internal.runtime

import priv.kit.PrivilegeStartupException
import priv.kit.PrivilegeStartupLogLine
import priv.kit.PrivilegeStartupLogListener
import priv.kit.isPrivKitInternalMetadata
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object PrivilegeRootStarter {
    internal fun isRootAvailable(): Boolean {
        val process = try {
            ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return false
        }

        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return false
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.exitValue() == 0 && output.contains("uid=0")
    }

    @Throws(PrivilegeStartupException::class)
    internal fun start(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeRootProcess {
        if (!isRootAvailable()) {
            throw PrivilegeStartupException("Root is not available")
        }

        val output = PrivilegeRootProcess.Output(startupLogListener)
        val process = startSuProcess(commandLine, output)
        return PrivilegeRootProcess(process, output)
    }

    @Throws(PrivilegeStartupException::class)
    private fun startSuProcess(
        commandLine: String,
        output: PrivilegeRootProcess.Output,
    ): Process {
        val process = try {
            ProcessBuilder("su", "-c", commandLine).start()
        } catch (e: Exception) {
            throw PrivilegeStartupException("Failed to execute su", e)
        }
        consume(process.inputStream, output, "stdout")
        consume(process.errorStream, output, "stderr")
        return process
    }

    private fun consume(
        inputStream: InputStream,
        output: PrivilegeRootProcess.Output,
        source: String,
    ) {
        thread(name = "PrivilegeRootStarter-$source", isDaemon = true) {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.append(source, it) }
            }
        }
    }
}

internal class PrivilegeRootProcess internal constructor(
    private val process: Process,
    private val output: Output,
) {
    internal val isAlive: Boolean
        get() = process.isAlive

    internal fun destroy() {
        process.destroy()
    }

    internal fun outputText(): String = output.text()

    internal class Output(
        private val startupLogListener: PrivilegeStartupLogListener? = null,
    ) {
        private val lines = Collections.synchronizedList(mutableListOf<String>())

        internal fun append(source: String, line: String) {
            val startupLogLine = PrivilegeStartupLogLine(
                source = source,
                message = line,
            )
            if (lines.size < MAX_CAPTURED_LINES && !startupLogLine.isPrivKitInternalMetadata) {
                lines += "[$source] $line"
            }
            startupLogLine.emitIfDisplayable()
        }

        internal fun text(): String = lines.joinToString("\n").ifBlank { "<no output>" }

        private fun PrivilegeStartupLogLine.emitIfDisplayable() {
            if (!isPrivKitInternalMetadata) {
                startupLogListener?.onLog(this)
            }
        }
    }

    private companion object {
        private const val MAX_CAPTURED_LINES = 40
    }
}
