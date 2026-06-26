package priv.kit.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceManagerBinderTest {
    @Test
    fun declarationExceptionMapsToErrorBundle() {
        val manager = manager(EmbeddedHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = "missing.UserService",
                processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
            ),
        )

        val response = manager.startUserService(request, FakeBinder())

        assertError(response, PrivilegeUserServiceContract.ERROR_DECLARATION)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("missing.UserService"),
        )
    }

    @Test
    fun malformedRequestMapsToDeclarationErrorBundle() {
        val response = manager(EmbeddedHost()).startUserService(android.os.Bundle(), FakeBinder())

        assertError(response, PrivilegeUserServiceContract.ERROR_DECLARATION)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains(PrivilegeUserServiceContract.KEY_SERVICE_CLASS_NAME),
        )
    }

    @Test
    fun startExceptionMapsToErrorBundle() {
        val host = DedicatedHost(
            process = object : FakeUserServiceProcess() {
                override fun start() {
                    throw IllegalStateException("start exploded")
                }
            },
        )
        val response = manager(host).startUserService(dedicatedRequest(), FakeBinder())

        assertError(response, PrivilegeUserServiceContract.ERROR_START)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("Dedicated UserService start failed"),
        )
    }

    @Test
    fun bindExceptionMapsToErrorBundle() {
        val host = DedicatedHost(
            process = object : FakeUserServiceProcess() {
                override fun bind(): IBinder {
                    throw IllegalStateException("bind exploded")
                }
            },
        )
        val response = manager(host).bindUserService(dedicatedRequest(), FakeBinder())

        assertError(response, PrivilegeUserServiceContract.ERROR_BIND)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("Dedicated UserService bind failed"),
        )
    }

    @Test
    fun notRunningExceptionMapsToErrorBundle() {
        val response = manager(EmbeddedHost()).unbindUserService("missing-connection")

        assertError(response, PrivilegeUserServiceContract.ERROR_NOT_RUNNING)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("missing-connection"),
        )
    }

    @Test
    fun unexpectedThrowableMapsToUnavailableErrorBundle() {
        val host = object : DedicatedHost(FakeUserServiceProcess()) {
            override fun startDedicatedProcess(
                spec: PrivilegeUserServiceSpec,
                token: String,
            ): Process {
                throw IllegalStateException("host exploded")
            }
        }

        val response = manager(host).startUserService(dedicatedRequest(), FakeBinder())

        assertError(response, PrivilegeUserServiceContract.ERROR_UNAVAILABLE)
        assertEquals("host exploded", response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE))
    }

    private fun manager(host: PrivilegeUserServiceHost): PrivilegeUserServiceManagerBinder =
        PrivilegeUserServiceManagerBinder(
            PrivilegeUserServiceRegistry(
                host = host,
                dedicatedStartTimeoutMillis = 1L,
            ),
        )

    private fun dedicatedRequest(): android.os.Bundle =
        PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = "test.UserService",
                processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
            ),
        )

    private fun assertError(
        response: android.os.Bundle,
        type: String,
    ) {
        assertFalse(response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, true))
        assertEquals(type, response.getString(PrivilegeUserServiceContract.KEY_ERROR_TYPE))
        assertNotNull(response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE))
    }

    private open class EmbeddedHost : PrivilegeUserServiceHost {
        override val uid: Int = 0
        override val pid: Int = 1234
        override val packageName: String = "priv.kit.test"
        override val userId: Int = 0

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): Process {
            error("Dedicated process is not used by this test")
        }

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess {
            error("Dedicated process is not used by this test")
        }

        override fun awaitDedicatedProcessExit(
            process: Process,
            timeoutMillis: Long,
        ): Boolean = true

        override fun killDedicatedProcess(process: Process) = Unit
    }

    private open class DedicatedHost(
        private val process: IPrivilegeUserServiceProcess,
    ) : PrivilegeUserServiceHost {
        override val uid: Int = 0
        override val pid: Int = 1234
        override val packageName: String = "priv.kit.test"
        override val userId: Int = 0

        override fun startDedicatedProcess(
            spec: PrivilegeUserServiceSpec,
            token: String,
        ): Process =
            FakeProcess()

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess = process

        override fun awaitDedicatedProcessExit(
            process: Process,
            timeoutMillis: Long,
        ): Boolean = true

        override fun killDedicatedProcess(process: Process) = Unit
    }

    private open class FakeUserServiceProcess : IPrivilegeUserServiceProcess {
        private val binder = FakeBinder(localInterface = this)

        override fun asBinder(): IBinder = binder

        override fun getPid(): Int = 4321

        override fun start() = Unit

        override fun bind(): IBinder = binder

        override fun unbind(connectionId: String) = Unit

        override fun destroy() = Unit
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
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
}
