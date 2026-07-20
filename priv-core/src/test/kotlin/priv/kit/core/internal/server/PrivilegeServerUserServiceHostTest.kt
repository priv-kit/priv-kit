package priv.kit.core.internal.server

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceException
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerUserServiceHostTest {
    @Test
    fun buildsDedicatedProcessCommand() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = $$"com.example.My$Service",
            tag = "tag with/slash",
        )

        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(),
            spec = spec,
            token = "token-1",
            serverPid = 2468,
        )

        assertEquals(
            "priv.kit.sample:My-Service-tag-with-slash",
            command.processName,
        )
        assertEquals(mapOf("CLASSPATH" to "/data/app/base.apk"), command.environment)
        assertEquals(
            listOf(
                "/system/bin/app_process",
                "/system/bin",
                "--nice-name=priv.kit.sample:My-Service-tag-with-slash",
                "priv.kit.core.internal.userservice.PrivilegeUserServiceMain",
                "--token",
                "token-1",
                "--provider-authority",
                "priv.kit.sample.privilege.handshake",
                "--package-name",
                "priv.kit.sample",
                "--user-id",
                "10",
                "--service-class",
                $$"com.example.My$Service",
                "--server-pid",
                "2468",
            ),
            command.arguments,
        )
    }

    @Test
    fun processNameOmitsDedicatedTagAndTruncatesSuffix() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = "com.example." + "VeryLongServiceName".repeat(4),
            tag = "dedicated",
        )

        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(),
            spec = spec,
            token = "token-1",
            serverPid = 2468,
        )

        assertEquals(48, command.processName.substringAfter(':').length)
        assertEquals(
            "priv.kit.sample:VeryLongServiceNameVeryLongServiceNameVeryLongSe",
            command.processName,
        )
    }

    @Test
    fun commandRequiresClasspath() {
        assertThrows(PrivilegeUserServiceException::class.java) {
            PrivilegeServerUserServiceProcessCommand.build(
                config = config(classpath = ""),
                spec = PrivilegeUserServiceSpec(serviceClassName = "test.UserService"),
                token = "token-1",
                serverPid = 2468,
            )
        }
    }

    @Test
    fun hostStartsProcessFromBuiltCommand() {
        val startedCommands = mutableListOf<PrivilegeServerUserServiceProcessStartCommand>()
        val process = TestProcess()
        val host = PrivilegeServerUserServiceHost(
            config = config(),
            processStarter = { command ->
                startedCommands += command
                process
            },
        )

        val startedProcess = host.startDedicatedProcess(
            spec = PrivilegeUserServiceSpec(serviceClassName = "test.UserService"),
            token = "token-1",
        )

        assertSame(process, startedProcess)
        assertEquals("token-1", startedCommands.single().arguments[5])
    }

    @Test
    fun claimerReturnsClaimedProcessBinder() {
        val process = TestUserServiceProcess()
        val claimer = PrivilegeServerUserServiceProcessClaimer(
            elapsedRealtime = { 0L },
            sleep = { error("sleep should not be needed") },
            providerCall = { _, _ ->
                Bundle().apply {
                    putBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, true)
                    putBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER, process.asBinder())
                }
            },
        )

        val claimed = claimer.await(
            config = config(),
            token = "token-1",
            timeoutMillis = 1_000L,
        )

        assertSame(process, claimed)
    }

    @Test
    fun claimerTimeoutKeepsLastProviderFailure() {
        var now = 0L
        val providerFailure = IllegalStateException("provider exploded")
        val claimer = PrivilegeServerUserServiceProcessClaimer(
            elapsedRealtime = { now },
            sleep = { delayMillis -> now += delayMillis },
            providerCall = { _, _ -> throw providerFailure },
        )

        val throwable = assertThrows(PrivilegeUserServiceException::class.java) {
            claimer.await(
                config = config(),
                token = "token-1",
                timeoutMillis = 50L,
            )
        }

        assertEquals("Timed out waiting for dedicated UserService process", throwable.message)
        assertSame(providerFailure, throwable.cause)
    }

    @Test
    fun killFallsBackToDestroyWhenDestroyForciblyFails() {
        val process = RecordingProcess()

        PrivilegeServerUserServiceProcessKiller.kill(process)

        assertEquals(1, process.destroyForciblyCalls)
        assertEquals(1, process.destroyCalls)
    }

    private class RecordingProcess : TestProcess() {
        var destroyForciblyCalls = 0
        var destroyCalls = 0

        override fun destroyForcibly(): Process {
            destroyForciblyCalls += 1
            throw IllegalStateException("destroyForcibly exploded")
        }

        override fun destroy() {
            destroyCalls += 1
        }
    }

    private companion object {
        fun config(classpath: String = "/data/app/base.apk"): PrivilegeServerConfig =
            PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                userId = 10,
                classpath = classpath,
            )
    }
}
