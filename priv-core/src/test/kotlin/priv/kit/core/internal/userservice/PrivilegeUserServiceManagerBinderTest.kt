package priv.kit.core.internal.userservice

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceManagerBinderTest {
    @Test
    fun declarationExceptionMapsToErrorBundle() {
        val manager = manager(TestEmbeddedUserServiceHost())
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
        val response = manager(TestEmbeddedUserServiceHost()).startUserService(android.os.Bundle(), TestBinder())

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains(PrivilegeUserServiceContract.KEY_SERVICE_CLASS_NAME),
        )
    }

    @Test
    fun startExceptionMapsToErrorBundle() {
        val host = TestDedicatedUserServiceHost(
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
        val host = TestDedicatedUserServiceHost(
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
        val response = manager(TestEmbeddedUserServiceHost()).unbindUserService("missing-connection")

        assertError(response)
        assertTrue(
            response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
                .orEmpty()
                .contains("missing-connection"),
        )
    }

    @Test
    fun startProcessFailureMapsToStartErrorBundle() {
        val host = object : TestDedicatedUserServiceHost(TestUserServiceProcess()) {
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

}
