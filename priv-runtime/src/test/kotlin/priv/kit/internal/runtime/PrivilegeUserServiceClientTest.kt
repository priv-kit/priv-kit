package priv.kit.internal.runtime

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.binder.PrivilegeServerUnavailableException
import priv.kit.internal.userservice.IPrivilegeUserServiceManager
import priv.kit.internal.userservice.PrivilegeUserServiceContract
import priv.kit.testing.TestBinder
import priv.kit.userservice.PrivilegeUserServiceException
import priv.kit.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceClientTest {
    @Test
    fun startBindUnbindAndStopUseManagerProtocol() {
        val spec = spec()
        val serviceBinder = TestBinder()
        val manager = RecordingManager(spec)
        manager.startResult = {
            PrivilegeUserServiceContract.successBundle()
        }
        manager.bindResult = {
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = "connection-1",
                binder = serviceBinder,
            )
        }
        manager.unbindResult = {
            PrivilegeUserServiceContract.successBundle()
        }
        manager.stopResult = {
            PrivilegeUserServiceContract.successBundle()
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        client.start(spec)
        val connection = client.bind(spec)
        connection.close()
        client.stop(spec)

        assertEquals("connection-1", connection.id)
        assertSame(serviceBinder, connection.binder)
        assertEquals(
            listOf("start", "bind", "unbind", "stop"),
            manager.calls,
        )
        assertEquals(spec, PrivilegeUserServiceContract.specFrom(manager.startRequests.single()))
        assertEquals(spec, PrivilegeUserServiceContract.specFrom(manager.bindRequests.single()))
        assertEquals(spec, PrivilegeUserServiceContract.specFrom(manager.stopRequests.single()))
        assertEquals(listOf("connection-1"), manager.unbindIds)
        assertNotNull(manager.startClients.single())
        assertNotNull(manager.bindClients.single())
    }

    @Test
    fun errorBundleMapsToUserServiceException() {
        val manager = RecordingManager(spec())
        manager.startResult = {
            PrivilegeUserServiceContract.errorBundle("broken")
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUserServiceException::class.java) {
            client.start(spec())
        }

        assertEquals("broken", throwable.message)
    }

    @Test
    fun managerProviderServerUnavailablePropagates() {
        val providerFailure = PrivilegeServerUnavailableException()
        val providerException = assertThrows(PrivilegeServerUnavailableException::class.java) {
            PrivilegeUserServiceClient { throw providerFailure }.start(spec())
        }
        assertSame(providerFailure, providerException)
    }

    @Test
    fun managerRemoteExceptionSurfacesServerUnavailable() {
        val manager = RecordingManager(spec())
        val remoteException = RemoteException("remote exploded")
        manager.startResult = {
            throw remoteException
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeServerUnavailableException::class.java) {
            client.start(spec())
        }

        assertSame(remoteException, throwable.cause)
    }

    @Test
    fun bindRequiresConnectionIdAndServiceBinder() {
        val firstManager = RecordingManager(spec())
        firstManager.bindResult = {
            PrivilegeUserServiceContract.successBundle()
        }
        assertThrows(PrivilegeUserServiceException::class.java) {
            PrivilegeUserServiceClient { firstManager.binder }.bind(spec())
        }

        val secondManager = RecordingManager(spec())
        secondManager.bindResult = {
            PrivilegeUserServiceContract.successBundle().apply {
                putString(PrivilegeUserServiceContract.KEY_CONNECTION_ID, "connection-1")
            }
        }
        assertThrows(PrivilegeUserServiceException::class.java) {
            PrivilegeUserServiceClient { secondManager.binder }.bind(spec())
        }
    }

    private class RecordingManager(
        @Suppress("UNUSED_PARAMETER")
        defaultSpec: PrivilegeUserServiceSpec,
    ) : IPrivilegeUserServiceManager {
        val binder = TestBinder(localInterface = this)
        val calls = mutableListOf<String>()
        val startRequests = mutableListOf<Bundle>()
        val bindRequests = mutableListOf<Bundle>()
        val stopRequests = mutableListOf<Bundle>()
        val unbindIds = mutableListOf<String>()
        val startClients = mutableListOf<IBinder?>()
        val bindClients = mutableListOf<IBinder?>()

        var startResult: () -> Bundle = {
            PrivilegeUserServiceContract.successBundle()
        }
        var bindResult: () -> Bundle = {
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = "connection",
                binder = TestBinder(),
            )
        }
        var unbindResult: () -> Bundle = {
            PrivilegeUserServiceContract.successBundle()
        }
        var stopResult: () -> Bundle = {
            PrivilegeUserServiceContract.successBundle()
        }

        override fun asBinder(): IBinder = binder

        override fun startUserService(
            request: Bundle,
            client: IBinder?,
        ): Bundle {
            calls += "start"
            startRequests += request
            startClients += client
            return startResult()
        }

        override fun bindUserService(
            request: Bundle,
            client: IBinder?,
        ): Bundle {
            calls += "bind"
            bindRequests += request
            bindClients += client
            return bindResult()
        }

        override fun unbindUserService(connectionId: String): Bundle {
            calls += "unbind"
            unbindIds += connectionId
            return unbindResult()
        }

        override fun stopUserService(request: Bundle): Bundle {
            calls += "stop"
            stopRequests += request
            return stopResult()
        }
    }

    private companion object {
        fun spec(): PrivilegeUserServiceSpec =
            PrivilegeUserServiceSpec(serviceClassName = "test.UserService")
    }
}
