package priv.kit.core.internal.runtime

import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.ResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.binder.PrivilegeServerUnavailableException
import priv.kit.core.internal.userservice.IPrivilegeUserServiceManager
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.testing.TestBinder
import priv.kit.core.userservice.PrivilegeUserServiceException
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceClientTest {
    @Test
    fun startBindUnbindAndStopUseManagerProtocol() = runBlocking {
        val spec = spec()
        val serviceBinder = TestBinder()
        val manager = RecordingManager()
        manager.bindResult = {
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = "connection-1",
                binder = serviceBinder,
            )
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        client.start(spec)
        val connection = client.bind(spec)
        connection.unbind()
        client.stop(spec)

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
        assertEquals(4, manager.acknowledgedOperationIds.size)
        assertEquals(emptyList<String>(), manager.cancelledOperationIds)
    }

    @Test
    fun errorBundleMapsToUserServiceException() {
        val manager = RecordingManager().apply {
            startResult = { PrivilegeUserServiceContract.errorBundle("broken") }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUserServiceException::class.java) {
            runBlocking { client.start(spec()) }
        }

        assertEquals("broken", throwable.message)
        assertEquals(1, manager.cancelledOperationIds.size)
    }

    @Test
    fun missingResultDataMapsToUserServiceException() {
        val manager = RecordingManager().apply {
            startResult = { null }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUserServiceException::class.java) {
            runBlocking { client.start(spec()) }
        }

        assertEquals("UserService response is missing result data", throwable.message)
        assertEquals(1, manager.cancelledOperationIds.size)
    }

    @Test
    fun cancellationCancelsPendingManagerOperation() = runBlocking {
        val manager = RecordingManager().apply {
            completeStart = false
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val job = launch {
            client.start(spec())
        }

        val operationId = manager.startCalled.await()
        job.cancelAndJoin()

        assertEquals(listOf(operationId), manager.cancelledOperationIds)
        assertEquals(emptyList<String>(), manager.acknowledgedOperationIds)
    }

    @Test
    fun stopCancellationCancelsPendingManagerOperation() = runBlocking {
        val manager = RecordingManager().apply {
            completeStop = false
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val job = launch {
            client.stop(spec())
        }

        val operationId = manager.stopCalled.await()
        job.cancelAndJoin()

        assertEquals(listOf(operationId), manager.cancelledOperationIds)
        assertEquals(emptyList<String>(), manager.acknowledgedOperationIds)
    }

    @Test
    fun managerDeathFailsPendingOperation() = runBlocking {
        val manager = RecordingManager().apply {
            completeStart = false
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val failure = async {
            runCatching { client.start(spec()) }.exceptionOrNull()
        }

        manager.startCalled.await()
        manager.binder.killBinder(notifyDeathRecipients = true)

        val throwable = withTimeout(5_000L) { failure.await() }
        assertTrue(throwable is DeadObjectException)
    }

    @Test
    fun managerProviderServerUnavailablePropagates() {
        val providerFailure = PrivilegeServerUnavailableException()
        val providerException = assertThrows(PrivilegeServerUnavailableException::class.java) {
            runBlocking {
                PrivilegeUserServiceClient { throw providerFailure }.start(spec())
            }
        }
        assertEquals(providerFailure.message, providerException.message)
    }

    @Test
    fun managerDeadObjectExceptionPropagatesAsEndpointDeath() {
        val deadObjectException = DeadObjectException("server died")
        val manager = RecordingManager().apply {
            startResult = { throw deadObjectException }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(DeadObjectException::class.java) {
            runBlocking { client.start(spec()) }
        }

        assertSame(deadObjectException, throwable)
    }

    @Test
    fun managerRemoteExceptionPropagatesWithoutMisclassifyingServerState() {
        val remoteException = RemoteException("remote exploded")
        val manager = RecordingManager().apply {
            startResult = { throw remoteException }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(RemoteException::class.java) {
            runBlocking { client.start(spec()) }
        }

        assertSame(remoteException, throwable)
    }

    @Test
    fun bindRequiresConnectionIdAndServiceBinder() {
        val firstManager = RecordingManager().apply {
            bindResult = { PrivilegeUserServiceContract.successBundle() }
        }
        assertThrows(PrivilegeUserServiceException::class.java) {
            runBlocking {
                PrivilegeUserServiceClient { firstManager.binder }.bind(spec())
            }
        }

        val secondManager = RecordingManager().apply {
            bindResult = {
                PrivilegeUserServiceContract.successBundle().apply {
                    putString(PrivilegeUserServiceContract.KEY_CONNECTION_ID, "connection-1")
                }
            }
        }
        assertThrows(PrivilegeUserServiceException::class.java) {
            runBlocking {
                PrivilegeUserServiceClient { secondManager.binder }.bind(spec())
            }
        }
        assertEquals(1, firstManager.cancelledOperationIds.size)
        assertEquals(1, secondManager.cancelledOperationIds.size)
    }

    private class RecordingManager : IPrivilegeUserServiceManager {
        val binder = TestBinder(localInterface = this)
        val calls = mutableListOf<String>()
        val startRequests = mutableListOf<Bundle>()
        val bindRequests = mutableListOf<Bundle>()
        val stopRequests = mutableListOf<Bundle>()
        val unbindIds = mutableListOf<String>()
        val startClients = mutableListOf<IBinder?>()
        val bindClients = mutableListOf<IBinder?>()
        val stopClients = mutableListOf<IBinder?>()
        val cancelledOperationIds = mutableListOf<String>()
        val acknowledgedOperationIds = mutableListOf<String>()
        val startCalled = CompletableDeferred<String>()
        val stopCalled = CompletableDeferred<String>()

        var completeStart: Boolean = true
        var completeStop: Boolean = true
        var startResult: () -> Bundle? = {
            PrivilegeUserServiceContract.successBundle()
        }
        var bindResult: () -> Bundle = {
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = "connection",
                binder = TestBinder(),
            )
        }

        override fun asBinder(): IBinder = binder

        override fun startUserService(
            operationId: String,
            request: Bundle,
            client: IBinder?,
            receiver: ResultReceiver?,
        ) {
            calls += "start"
            startRequests += request
            startClients += client
            startCalled.complete(operationId)
            if (completeStart) {
                receiver?.send(0, startResult())
            }
        }

        override fun bindUserService(
            operationId: String,
            request: Bundle,
            client: IBinder?,
            receiver: ResultReceiver?,
        ) {
            calls += "bind"
            bindRequests += request
            bindClients += client
            receiver?.send(0, bindResult())
        }

        override fun cancelUserServiceOperation(operationId: String) {
            cancelledOperationIds += operationId
        }

        override fun acknowledgeUserServiceOperation(operationId: String) {
            acknowledgedOperationIds += operationId
        }

        override fun unbindUserService(
            operationId: String,
            connectionId: String,
            client: IBinder?,
            receiver: ResultReceiver?,
        ) {
            calls += "unbind"
            unbindIds += connectionId
            receiver?.send(0, PrivilegeUserServiceContract.successBundle())
        }

        override fun stopUserService(
            operationId: String,
            request: Bundle,
            client: IBinder?,
            receiver: ResultReceiver?,
        ) {
            calls += "stop"
            stopRequests += request
            stopClients += client
            stopCalled.complete(operationId)
            if (completeStop) {
                receiver?.send(0, PrivilegeUserServiceContract.successBundle())
            }
        }
    }

    private companion object {
        fun spec(): PrivilegeUserServiceSpec =
            PrivilegeUserServiceSpec(serviceClassName = "test.UserService")
    }
}
