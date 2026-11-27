package priv.kit.core.internal.server

import android.os.Bundle
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.core.userservice.PrivilegeUserServiceProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerUserServiceHostTest {
    @Test
    fun explicitUidUsesNativeLauncherOnlyWhenIdentityChanges() {
        for (process in listOf(PrivilegeUserServiceProcess.ROOT, PrivilegeUserServiceProcess.SYSTEM, PrivilegeUserServiceProcess.SHELL)) {
            val uid = process.uid
            val spec = PrivilegeUserServiceSpec(
                process = process,
            )
            val command = PrivilegeServerUserServiceProcessCommand.build(
                config(), spec, "token", currentUid = if (uid == 0) 2000 else 0,
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
    fun nonRootSwitchIsAttemptedByNativeLauncher() {
        val command = PrivilegeServerUserServiceProcessCommand.build(
            config(), PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.SYSTEM),
            "token", currentUid = 2000,
            uidLauncher = { listOf("starter") },
        )
        assertEquals(listOf("starter", "--user-service", "1000"), command.arguments.take(3))
    }
    @Test
    fun buildsDedicatedProcessCommand() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = $$"com.example.My$Service",
            tag = "tag with/slash",
            process = PrivilegeUserServiceProcess.INHERIT,
        )

        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(classpath = "/data/app/base.apk"),
            spec = spec,
            token = "token-1",
            currentUid = 2000,
        )

        assertEquals("/data/app/base.apk", command.environment["CLASSPATH"])
        assertEquals("[]", command.environment[PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORIES])
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
    fun forwardsCrashDirectoriesToDedicatedProcess() {
        val root = File(System.getProperty("java.io.tmpdir"))
        val directories = listOf(File(root, "internal"), File(root, "external"))
        val command = PrivilegeServerUserServiceProcessCommand.build(
            config = config(classpath = "/data/app/base.apk"),
            spec = PrivilegeUserServiceSpec(serviceClassName = "example.Service", process = PrivilegeUserServiceProcess.INHERIT),
            token = "token",
            currentUid = 0,
            crashLogDirectories = directories,
        )
        val paths = JSONArray(command.environment.getValue(PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORIES))
        assertEquals(directories.map { it.absolutePath }, List(paths.length()) { paths.getString(it) })
    }

    @Test
    fun processNameIncludesOnlyExplicitTags() {
        val cases = listOf(
            Triple(null, null, "binder"),
            Triple("com.example.MyService", null, "MyService"),
            Triple(null, "default", "binder-default"),
            Triple(null, "dedicated", "binder-dedicated"),
            Triple("com.example.MyService", "worker", "MyService-worker"),
        )
        for ((serviceClassName, tag, suffix) in cases) {
            val command = PrivilegeServerUserServiceProcessCommand.build(
                config = config(),
                spec = PrivilegeUserServiceSpec(
                    serviceClassName = serviceClassName,
                    tag = tag,
                    process = PrivilegeUserServiceProcess.INHERIT,
                ),
                token = "token-1",
                currentUid = 2000,
            )
            assertEquals(
                "--nice-name=priv.kit.sample:$suffix",
                command.arguments.single { it.startsWith("--nice-name=") },
            )
        }
    }

    @Test
    fun processNameTruncatesSuffix() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = "com.example." + "VeryLongServiceName".repeat(4),
            process = PrivilegeUserServiceProcess.INHERIT,
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
            spec = PrivilegeUserServiceSpec(serviceClassName = "test.UserService", process = PrivilegeUserServiceProcess.INHERIT),
            token = "token-1",
        )

        assertSame(process, startedProcess)
        assertEquals("token-1", startedCommands.single().arguments[5])
    }

    @Test
    fun nativePermissionFailureReachesCaller() {
        val message = "UserService setresuid failed: Operation not permitted"
        val process = object : TestProcess() {
            override fun getInputStream() = message.byteInputStream()
            override fun exitValue(): Int = 1
        }
        val host = PrivilegeServerUserServiceHost(
            config = config(),
            processStarter = { process },
        )
        val handle = host.startDedicatedProcess(PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.INHERIT), "failed-process")
        val failure = assertThrows(IllegalStateException::class.java) {
            host.awaitDedicatedProcess("failed-process", handle, 1_000L)
        }
        val callerFailure = assertThrows(IllegalStateException::class.java) {
            PrivilegeUserServiceContract.throwFailure(PrivilegeUserServiceContract.errorBundle(failure))
        }
        assertEquals("UserService exited with code 1: $message", callerFailure.message)
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
