package priv.kit.root

import priv.kit.core.PrivilegeStartupException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PrivilegeRootStarter {
    fun isRootAvailable(): Boolean {
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
    fun start(command: PrivilegeRootCommand): PrivilegeRootStartResult {
        if (!isRootAvailable()) {
            throw PrivilegeStartupException("Root is not available")
        }

        val output = PrivilegeRootProcess.Output()
        val process = startSuProcess(command.commandLine, output)
        return PrivilegeRootStartResult(
            command = command,
            process = PrivilegeRootProcess(process, output),
        )
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
