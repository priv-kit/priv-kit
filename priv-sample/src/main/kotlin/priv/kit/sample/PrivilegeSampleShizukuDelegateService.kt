package priv.kit.sample

import android.content.Context
import androidx.annotation.Keep
import java.io.InputStream
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
internal class PrivilegeSampleShizukuDelegateService @Keep constructor() :
    IPrivilegeSampleShizukuDelegateService.Stub() {
    private val output = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var process: Process? = null

    @Keep
    constructor(context: Context) : this() {
        appendOutput("diag", "Context constructor package=${context.packageName}")
    }

    override fun start(commandLine: String): String {
        require(commandLine.isNotBlank()) { "commandLine must not be blank" }
        stopLaunchProcess()
        output.clear()
        appendOutput(
            "diag",
            "Starting Priv Kit detached activation command uid=${android.os.Process.myUid()}, length=${commandLine.length}",
        )
        val startedProcess = try {
            ProcessBuilder("/system/bin/sh", "-c", commandLine).start()
        } catch (throwable: Throwable) {
            appendOutput("diag", throwable.toOutputLine("Failed to start command"))
            throw throwable
        }
        process = startedProcess
        consume(startedProcess.inputStream, "stdout")
        consume(startedProcess.errorStream, "stderr")
        return getLaunchOutput()
    }

    override fun isLaunchProcessAlive(): Boolean =
        process?.isAlive == true

    override fun getLaunchOutput(): String =
        synchronized(output) {
            output.joinToString("\n").ifBlank { "<no output>" }
        }

    override fun stopLaunchProcess() {
        val current = process
        if (current != null && current.isAlive) {
            appendOutput("diag", "Destroying launch process")
            current.destroy()
        }
        process = null
    }

    override fun destroy() {
        stopLaunchProcess()
        appendOutput("diag", "Destroying Shizuku delegate UserService")
        exitProcess(0)
    }

    private fun consume(
        inputStream: InputStream,
        source: String,
    ) {
        thread(name = "priv-kit-shizuku-delegate-$source", isDaemon = true) {
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
        const val MAX_CAPTURED_LINES = 80
    }
}
