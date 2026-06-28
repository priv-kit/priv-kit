package priv.kit.runtime

import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.userservice.IPrivilegeUserServiceManager
import priv.kit.userservice.PrivilegeUserServiceBindException
import priv.kit.userservice.PrivilegeUserServiceContract
import priv.kit.userservice.PrivilegeUserServiceDeclarationException
import priv.kit.userservice.PrivilegeUserServiceManagerUnavailableException
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import priv.kit.userservice.PrivilegeUserServiceRemoteCallException
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeRuntimeUserServiceClientTest {
    @Test
    fun startBindUnbindAndStopUseManagerProtocol() {
        val spec = spec()
        val serviceBinder = FakeBinder()
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
        val client = PrivilegeRuntimeUserServiceClient { manager.binder }

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
    fun errorBundlesMapToTypedExceptions() {
        val cases = listOf(
            PrivilegeUserServiceContract.ERROR_DECLARATION to PrivilegeUserServiceDeclarationException::class.java,
            PrivilegeUserServiceContract.ERROR_START to PrivilegeUserServiceStartException::class.java,
            PrivilegeUserServiceContract.ERROR_BIND to PrivilegeUserServiceBindException::class.java,
            PrivilegeUserServiceContract.ERROR_NOT_RUNNING to PrivilegeUserServiceNotRunningException::class.java,
            PrivilegeUserServiceContract.ERROR_UNAVAILABLE to PrivilegeUserServiceManagerUnavailableException::class.java,
            "unknown" to PrivilegeUserServiceManagerUnavailableException::class.java,
        )

        cases.forEach { (type, expected) ->
            val manager = RecordingManager(spec())
            manager.startResult = {
                PrivilegeUserServiceContract.errorBundle(
                    type = type,
                    message = "broken $type",
                )
            }
            val client = PrivilegeRuntimeUserServiceClient { manager.binder }

            val throwable = assertThrows(expected) {
                client.start(spec())
            }
            if (expected != PrivilegeUserServiceManagerUnavailableException::class.java) {
                assertEquals("broken $type", throwable.message)
            }
        }
    }

    @Test
    fun managerProviderFailuresSurfaceUnavailable() {
        assertThrows(PrivilegeUserServiceManagerUnavailableException::class.java) {
            PrivilegeRuntimeUserServiceClient { null }.start(spec())
        }

        val providerFailure = IllegalStateException("provider exploded")
        val providerException = assertThrows(PrivilegeUserServiceManagerUnavailableException::class.java) {
            PrivilegeRuntimeUserServiceClient { throw providerFailure }.start(spec())
        }
        assertSame(providerFailure, providerException.cause)

        val deadManager = RecordingManager(spec())
        val deadBinder = FakeBinder(localInterface = deadManager, alive = false)
        assertThrows(PrivilegeUserServiceManagerUnavailableException::class.java) {
            PrivilegeRuntimeUserServiceClient { deadBinder }.start(spec())
        }
    }

    @Test
    fun managerDeadObjectSurfacesUnavailable() {
        val manager = RecordingManager(spec())
        manager.startResult = {
            throw DeadObjectException()
        }
        val client = PrivilegeRuntimeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUserServiceManagerUnavailableException::class.java) {
            client.start(spec())
        }

        assertEquals(DeadObjectException::class.java, throwable.cause?.javaClass)
    }

    @Test
    fun managerRemoteExceptionSurfacesRemoteCallException() {
        val manager = RecordingManager(spec())
        val remoteException = RemoteException("remote exploded")
        manager.startResult = {
            throw remoteException
        }
        val client = PrivilegeRuntimeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUserServiceRemoteCallException::class.java) {
            client.start(spec())
        }

        assertEquals("Failed to start UserService", throwable.message)
        assertSame(remoteException, throwable.cause)
    }

    @Test
    fun bindRequiresConnectionIdAndServiceBinder() {
        val firstManager = RecordingManager(spec())
        firstManager.bindResult = {
            PrivilegeUserServiceContract.successBundle()
        }
        assertThrows(PrivilegeUserServiceBindException::class.java) {
            PrivilegeRuntimeUserServiceClient { firstManager.binder }.bind(spec())
        }

        val secondManager = RecordingManager(spec())
        secondManager.bindResult = {
            PrivilegeUserServiceContract.successBundle().apply {
                putString(PrivilegeUserServiceContract.KEY_CONNECTION_ID, "connection-1")
            }
        }
        assertThrows(PrivilegeUserServiceBindException::class.java) {
            PrivilegeRuntimeUserServiceClient { secondManager.binder }.bind(spec())
        }
    }

    private class RecordingManager(
        @Suppress("UNUSED_PARAMETER")
        defaultSpec: PrivilegeUserServiceSpec,
    ) : IPrivilegeUserServiceManager {
        val binder = FakeBinder(localInterface = this)
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
                binder = FakeBinder(),
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
        fun spec(): PrivilegeUserServiceSpec =
            PrivilegeUserServiceSpec(serviceClassName = "test.UserService")
    }
}
