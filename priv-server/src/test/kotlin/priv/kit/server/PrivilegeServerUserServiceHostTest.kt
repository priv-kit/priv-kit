package priv.kit.server

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.userservice.IPrivilegeUserServiceProcess
import priv.kit.userservice.PrivilegeUserServiceContract
import priv.kit.userservice.PrivilegeUserServiceProcessMode
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerUserServiceHostTest {
    @Test
    fun buildsDedicatedProcessCommand() {
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = $$"com.example.My$Service",
            tag = "tag with/slash",
            processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
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
                "priv.kit.userservice.PrivilegeUserServiceMain",
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
            processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
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
        assertThrows(PrivilegeUserServiceStartException::class.java) {
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
        val process = FakeProcess()
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
        val process = FakeUserServiceProcess()
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

        val throwable = assertThrows(PrivilegeUserServiceStartException::class.java) {
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

    private class FakeUserServiceProcess : IPrivilegeUserServiceProcess {
        private val binder = FakeBinder(localInterface = this)

        override fun asBinder(): IBinder = binder

        override fun start() = Unit

        override fun bind(): IBinder = binder

        override fun unbind(connectionId: String) = Unit

        override fun destroy() = Unit
    }

    private open class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    private class RecordingProcess : FakeProcess() {
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

    private class FakeBinder(
        private val localInterface: IInterface? = null,
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        override fun getInterfaceDescriptor(): String = "fake"

        override fun pingBinder(): Boolean = alive

        override fun isBinderAlive(): Boolean = alive

        override fun queryLocalInterface(descriptor: String): IInterface? = localInterface

        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = alive

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (!alive) {
                throw RemoteException("Binder is dead")
            }
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = true
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
