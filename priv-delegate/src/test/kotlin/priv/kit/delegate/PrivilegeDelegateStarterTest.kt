package priv.kit.delegate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeLaunchMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeStartupException
import java.util.concurrent.atomic.AtomicReference

class PrivilegeDelegateStarterTest {
    @Test
    fun startPassesCommandToExecutor() {
        val receivedCommand = AtomicReference<PrivilegeDelegateCommand?>()
        val process = PrivilegeDelegateProcess.unmanaged("started")
        val executor = object : PrivilegeDelegateExecutor {
            override val name: String = "test-delegate"

            override fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess {
                receivedCommand.set(command)
                return process
            }
        }
        val command = command()

        val result = PrivilegeDelegateStarter(executor).start(command)

        assertSame(command, receivedCommand.get())
        assertEquals("test-delegate", result.executorName)
        assertSame(command, result.command)
        assertSame(process, result.process)
    }

    @Test
    fun unavailableExecutorThrowsStartupException() {
        val executor = object : PrivilegeDelegateExecutor {
            override val name: String = "offline-delegate"

            override fun isAvailable(): Boolean = false

            override fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess =
                error("should not start")
        }

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            PrivilegeDelegateStarter(executor).start(command())
        }

        assertEquals("Delegate executor is not available: offline-delegate", exception.message)
    }

    @Test
    fun throwingExecutorIsWrappedAsStartupException() {
        val failure = IllegalStateException("boom")
        val executor = object : PrivilegeDelegateExecutor {
            override val name: String = "throwing-delegate"

            override fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess {
                throw failure
            }
        }

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            PrivilegeDelegateStarter(executor).start(command())
        }

        assertEquals(
            "Delegate executor throwing-delegate failed to start Privileged Server",
            exception.message,
        )
        assertSame(failure, exception.cause)
    }

    @Test
    fun staticProcessesExposeManagedState() {
        val unmanaged = PrivilegeDelegateProcess.unmanaged("running")
        val completed = PrivilegeDelegateProcess.completed("done")

        assertTrue(unmanaged.isAlive)
        assertEquals("running", unmanaged.outputText())
        assertFalse(completed.isAlive)
        assertEquals("done", completed.outputText())
    }

    private fun command(): PrivilegeDelegateCommand {
        val launchCommand = PrivilegeServerLaunchCommand(
            token = "token-value",
            foregroundCommandLine = "foreground",
            detachedCommandLine = "detached",
            classpath = "/data/app/example/base.apk",
            classpathIdentity = "/data/app/example/base.apk@1@2",
            mainClass = "priv.kit.server.PrivilegeServerMain",
            providerAuthority = "example.privilege.handshake",
            packageName = "example",
            launchMode = PrivilegeLaunchMode.SHELL,
            protocolVersion = PrivilegeProtocol.VERSION,
            serverVersion = PrivilegeProtocol.SERVER_VERSION,
            userId = 10,
        )
        return PrivilegeDelegateCommand(
            foregroundCommandLine = launchCommand.foregroundCommandLine,
            detachedCommandLine = launchCommand.detachedCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
        )
    }
}
