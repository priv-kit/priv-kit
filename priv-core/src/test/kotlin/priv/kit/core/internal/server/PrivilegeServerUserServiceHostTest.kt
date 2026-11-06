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
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerUserServiceHostTest {
    @Test
    fun explicitUidUsesNativeLauncherOnlyWhenIdentityChanges() {
        for (uid in listOf(1000, 2000)) {
            val spec = PrivilegeUserServiceSpec(
                process = priv.kit.core.userservice.PrivilegeUserServiceProcess(uid),
            )
            val command = PrivilegeServerUserServiceProcessCommand.build(
                config(), spec, "token", currentUid = 0,
                uidLauncher = { listOf("/system/bin/linker64", "/app/base.apk!/lib/arm64-v8a/libprivkitstarter.so") },
            )
            assertEquals("--user-service", command.arguments[2])
            assertEquals(uid.toString(), command.arguments[3])
            assertEquals("/system/bin/app_process", command.arguments[4])
            assertEquals(false, "--service-class" in command.arguments)
            val inherited = PrivilegeServerUserServiceProcessCommand.build(config(), spec, "token", currentUid = uid)
            assertEquals("/system/bin/app_process", inherited.arguments.first())
        }
    }

    @Test
    fun nonRootCannotSwitchToDifferentUid() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerUserServiceProcessCommand.build(
                config(), PrivilegeUserServiceSpec(process = priv.kit.core.userservice.PrivilegeUserServiceProcess(1000)),
                "token", currentUid = 2000,
            )
        }
    }
    @Test
    fun buildsDedicatedProcessCommand() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = $$"com.example.My$Service",
            tag = "tag with/slash",
        )

        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(classpath = "/data/app/base.apk"),
            spec = spec,
            token = "token-1",
            currentUid = 0,
        )

        assertEquals("/data/app/base.apk", command.environment["CLASSPATH"])
        assertEquals("", command.environment[priv.kit.core.internal.core.PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORY])
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
            ),
            command.arguments,
        )
    }

    @Test
    fun forwardsCrashDirectoryToDedicatedProcess() {
        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(classpath = "/data/app/base.apk"),
            spec = PrivilegeUserServiceSpec(serviceClassName = "example.Service"),
            token = "token",
            currentUid = 0,
            crashLogDirectory = "/storage/emulated/10/Android/data/example.app/files/crashes",
        )
        assertEquals(
            "/storage/emulated/10/Android/data/example.app/files/crashes",
            command.environment[priv.kit.core.internal.core.PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORY],
        )
    }

    @Test
    fun processNameOmitsDedicatedTagAndTruncatesSuffix() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = "com.example." + "VeryLongServiceName".repeat(4),
            tag = "dedicated",
        )

        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(classpath = "/data/app/base.apk"),
            spec = spec,
            token = "token-1",
            currentUid = 0,
        )

        val niceName = command.arguments.single { it.startsWith("--nice-name=") }
            .substringAfter('=')
        assertEquals(48, niceName.substringAfter(':').length)
        assertEquals(
            "priv.kit.sample:VeryLongServiceNameVeryLongServiceNameVeryLongSe",
            niceName,
        )
    }

    @Test
    fun hostStartsProcessFromBuiltCommand() {
        val startedCommands = mutableListOf<PrivilegeServerUserServiceProcessStartCommand>()
        val process = TestProcess()
        val host = PrivilegeServerUserServiceHost(
            config = config(classpath = "/data/app/base.apk"),
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
            providerCall = { _, _ ->
                Bundle().apply {
                    putBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, true)
                    putBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER, process.asBinder())
                }
            },
        )

        val claimed = claimer.await(
            config = config(classpath = "/data/app/base.apk"),
            token = "token-1",
            timeoutMillis = 1_000L,
        )

        assertSame(process, claimed)
    }

    @Test
    fun claimerTimeoutKeepsLastProviderFailure() {
        val providerFailure = IllegalStateException("provider exploded")
        val claimer = PrivilegeServerUserServiceProcessClaimer(
            providerCall = { _, _ ->
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(50))
                throw providerFailure
            },
        )

        val throwable = assertThrows(IllegalStateException::class.java) {
            claimer.await(
                config = config(classpath = "/data/app/base.apk"),
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
        fun config(classpath: String = "/app/base.apk"): PrivilegeServerConfig =
            PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                userId = 10,
                classpath = classpath,
            )
    }
}
