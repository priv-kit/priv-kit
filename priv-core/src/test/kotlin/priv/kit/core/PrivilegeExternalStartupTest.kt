package priv.kit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.core.PrivilegeStartupLogListener
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PrivilegeExternalStartupTest {
    @Test
    fun runInCurrentProcessStreamsAndCapturesOutput() {
        val received = CopyOnWriteArrayList<String>()
        val process = FakeProcess(
            stdout = "ready\nmore\n",
            stderr = "warn\n",
            exitCode = 0,
        )
        var startedCommand: String? = null
        val runner = PrivilegeExternalStartupProcessRunner { options, commandLine ->
            startedCommand = "${options.shellPath}|$commandLine"
            process
        }

        val result = runner.run(
            commandLine = "start-command",
            options = PrivilegeExternalStartupOptions(
                shellPath = "/custom/sh",
                timeoutMillis = 1_000L,
            ),
            startupLogListener = PrivilegeStartupLogListener { line ->
                received += "[${line.source}] ${line.message}"
            },
        )

        assertEquals("/custom/sh|start-command", startedCommand)
        assertTrue(result.contains("[stdout] ready"))
        assertTrue(result.contains("[stdout] more"))
        assertTrue(result.contains("[stderr] warn"))
        assertTrue(result.contains("[diag] Shell start command exited code=0"))
        assertTrue(received.any { it.startsWith("[diag] Starting Priv Kit shell start command") })
        assertTrue(received.contains("[stdout] ready"))
        assertTrue(received.contains("[stdout] more"))
        assertTrue(received.contains("[stderr] warn"))
    }

    @Test
    fun runInCurrentProcessThrowsWithCapturedOutputForNonZeroExit() {
        val process = FakeProcess(
            stderr = "nope\n",
            exitCode = 7,
        )
        val runner = PrivilegeExternalStartupProcessRunner { _, _ -> process }

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            runner.run(
                commandLine = "bad-command",
                options = PrivilegeExternalStartupOptions(),
                startupLogListener = null,
            )
        }

        assertTrue(exception.message.orEmpty().contains("exited code=7"))
        assertTrue(exception.message.orEmpty().contains("[stderr] nope"))
    }

    @Test
    fun receiverPrefixesRemoteSourcesAndSanitizesSourceText() {
        val received = mutableListOf<String>()
        val receiver = PrivilegeExternalStartup.createReceiver(
            startupLogListener = PrivilegeStartupLogListener { line ->
                received += "[${line.source}] ${line.message}"
            },
            sourcePrefix = "shizuku",
        )

        receiver.receive("stdout", "ready")
        receiver.receive("bad\nsource", "still ready")
        receiver.receive(
            PrivilegeStartupLogLine(
                source = "stderr",
                message = "warn",
            ),
        )

        assertEquals(
            listOf(
                "[shizuku/stdout] ready",
                "[shizuku/bad source] still ready",
                "[shizuku/stderr] warn",
            ),
            received,
        )
    }

    private class FakeProcess(
        stdout: String = "",
        stderr: String = "",
        private val exitCode: Int,
    ) : Process() {
        private val stdoutStream = stdout.byteInputStream()
        private val stderrStream = stderr.byteInputStream()
        private val stdinStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdinStream

        override fun getInputStream() = stdoutStream

        override fun getErrorStream() = stderrStream

        override fun waitFor(): Int = exitCode

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun exitValue(): Int = exitCode

        override fun destroy() = Unit
    }
}
