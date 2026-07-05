package priv.kit.internal.userservice

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.testing.TestBinder
import priv.kit.testing.TestProcess
import priv.kit.testing.TestUserServiceProcess
import priv.kit.userservice.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceManagerBinderTest {
    @Test
    fun declarationExceptionMapsToErrorBundle() {
        val manager = manager(EmbeddedHost())
        val request = PrivilegeUserServiceContract.requestBundle(
            PrivilegeUserServiceSpec(
                serviceClassName = "missing.UserService",
                embedded = true,
            ),
        )

        val response = manager.startUserService(request, TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("missing.UserService"),
        )
    }

    @Test
    fun malformedRequestMapsToDeclarationErrorBundle() {
        val response = manager(EmbeddedHost()).startUserService(android.os.Bundle(), TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains(PrivilegeUserServiceContract.KEY_SERVICE_CLASS_NAME),
        )
    }

    @Test
    fun startExceptionMapsToErrorBundle() {
        val host = DedicatedHost(
            process = object : TestUserServiceProcess() {
                override fun start() {
                    throw IllegalStateException("start exploded")
                }
            },
        )
        val response = manager(host).startUserService(dedicatedRequest(), TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("Dedicated UserService start failed"),
        )
    }

    @Test
    fun bindExceptionMapsToErrorBundle() {
        val host = DedicatedHost(
            process = object : TestUserServiceProcess() {
                override fun bind(): IBinder {
                    throw IllegalStateException("bind exploded")
                }
            },
        )
        val response = manager(host).bindUserService(dedicatedRequest(), TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("Dedicated UserService bind failed"),
        )
    }

    @Test
    fun notRunningExceptionMapsToErrorBundle() {
        val response = manager(EmbeddedHost()).unbindUserService("missing-connection")

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("missing-connection"),
        )
    }

    @Test
    fun startProcessFailureMapsToStartErrorBundle() {
        val host = object : DedicatedHost(TestUserServiceProcess()) {
            override fun startDedicatedProcess(
                spec: PrivilegeUserServiceSpec,
                token: String,
            ): Process {
                throw IllegalStateException("host exploded")
            }
        }

        val response = manager(host).startUserService(dedicatedRequest(), TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("Dedicated UserService start failed"),
        )
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
            ),
        )

    private fun assertError(response: android.os.Bundle) {
        assertFalse(response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, true))
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
            TestProcess()

        override fun awaitDedicatedProcess(
            token: String,
            timeoutMillis: Long,
        ): IPrivilegeUserServiceProcess = process

        override fun killDedicatedProcess(process: Process) = Unit
    }

}
