package priv.kit.sample

import android.content.Context
import androidx.annotation.Keep
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
internal class PrivilegeSampleShizukuStartService @Keep constructor() :
    IPrivilegeSampleShizukuStartService.Stub() {
    private val output = Collections.synchronizedList(mutableListOf<String>())

    @Keep
    constructor(context: Context) : this() {
        appendOutput("diag", "Context constructor package=${context.packageName}")
    }

    override fun start(commandLine: String): String {
        require(commandLine.isNotBlank()) { "commandLine must not be blank" }
        output.clear()
        appendOutput(
            "diag",
            "Starting Priv Kit shell start command uid=${android.os.Process.myUid()}, length=${commandLine.length}",
        )
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", commandLine).start()
        } catch (throwable: Throwable) {
            appendOutput("diag", throwable.toOutputLine("Failed to start command"))
            throw throwable
        }
        consume(process.inputStream, "stdout")
        consume(process.errorStream, "stderr")

        if (!process.waitFor(SHELL_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            val message = "Shell start command did not return after ${SHELL_COMMAND_TIMEOUT_MILLIS}ms"
            appendOutput("diag", message)
            throw IllegalStateException(message)
        }

        val exitCode = process.exitValue()
        appendOutput("diag", "Shell start command exited code=$exitCode")
        if (exitCode != 0) {
            throw IllegalStateException(getLaunchOutput())
        }
        return getLaunchOutput()
    }

    override fun destroy() {
        appendOutput("diag", "Destroying Shizuku start UserService")
        exitProcess(0)
    }

    private fun getLaunchOutput(): String =
        synchronized(output) {
            output.joinToString("\n").ifBlank { "<no output>" }
        }

    private fun consume(
        inputStream: InputStream,
        source: String,
    ) {
        thread(name = "priv-kit-shizuku-start-$source", isDaemon = true) {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendOutput(source, line)
                }
            }
        }
    }

    private fun appendOutput(
        source: String,
        line: String,
    ) {
        synchronized(output) {
            if (output.size < MAX_CAPTURED_LINES) {
                output += "[$source] $line"
            }
        }
    }

    private fun Throwable.toOutputLine(prefix: String): String =
        "$prefix: ${javaClass.simpleName}: ${message.orEmpty()}"

    private companion object {
        const val SHELL_COMMAND_TIMEOUT_MILLIS = 2_000L
        const val MAX_CAPTURED_LINES = 80
    }
}
