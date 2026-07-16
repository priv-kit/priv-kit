package priv.kit.internal.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.cleanupRootProcessAfterStart
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class PrivilegeRootStarterTest {
    @Test
    fun rootProbeDestroysProcessAfterSuccessfulCheck() {
        val process = FakeProcess(output = "uid=0(root) gid=0(root)")

        assertTrue(rootProbeReportsAvailable(process))
        assertTrue(process.destroyed)
    }

    @Test
    fun rootProbeDestroysProcessAndPreservesInterruptedException() {
        val interrupted = InterruptedException("cancelled")
        val process = FakeProcess(waitFailure = interrupted)

        val thrown = assertThrows(InterruptedException::class.java) {
            rootProbeReportsAvailable(process)
        }

        assertSame(interrupted, thrown)
        assertTrue(process.destroyed)
    }

    @Test
    fun failedRootStartDestroysServerProcessOnlyOnAbnormalExit() {
        val failedProcess = FakeProcess()
        cleanupRootProcessAfterStart(
            process = PrivilegeRootProcess(failedProcess, PrivilegeRootProcess.Output()),
            startupCompleted = false,
        )
        assertTrue(failedProcess.destroyed)

        val successfulProcess = FakeProcess()
        cleanupRootProcessAfterStart(
            process = PrivilegeRootProcess(successfulProcess, PrivilegeRootProcess.Output()),
            startupCompleted = true,
        )
        assertFalse(successfulProcess.destroyed)
    }

    private class FakeProcess(
        private val output: String = "",
        private val waitFailure: InterruptedException? = null,
        private val finished: Boolean = true,
        private val exitCode: Int = 0,
    ) : Process() {
        var destroyed: Boolean = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitFailure?.let { throw it }
            return finished
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyed = true
        }
    }
}
