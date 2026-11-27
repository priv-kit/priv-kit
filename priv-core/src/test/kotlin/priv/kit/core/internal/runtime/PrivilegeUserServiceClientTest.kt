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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.internal.userservice.IPrivilegeUserServiceManager
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.testing.TestBinder
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceClientTest {
    @Test
    fun bindDefaultsToNonDaemon() = runBlocking {
        val manager = RecordingManager()
        val client = PrivilegeUserServiceClient { manager.binder }

        val connection = client.bind(spec())

        assertEquals(listOf(false), manager.bindDaemonValues)
        assertEquals(spec(), PrivilegeUserServiceContract.specFrom(manager.bindRequests.single()))
        connection.unbind()
    }

    @Test
    fun peekMissingServiceReturnsNullAndAcknowledgesOperation() = runBlocking {
        val manager = RecordingManager()
        val client = PrivilegeUserServiceClient { manager.binder }

        assertNull(client.peek(spec()))
        assertEquals(listOf("peek"), manager.calls)
        assertEquals(1, manager.acknowledgedOperationIds.size)
        assertEquals(emptyList<String>(), manager.cancelledOperationIds)
        assertEquals(0, manager.binder.deathRecipientCount)
    }

    @Test
    fun peekReturnsConnectionThatCanBeUnbound() = runBlocking {
        val serviceBinder = TestBinder()
        val manager = RecordingManager().apply {
            peekResult = {
                PrivilegeUserServiceContract.bindSuccessBundle(
                    connectionId = "peek-connection",
                    binder = serviceBinder,
                )
            }
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val connection = requireNotNull(client.peek(spec()))

        assertEquals(serviceBinder.interfaceDescriptor, connection.binder.interfaceDescriptor)
        assertTrue(connection.isConnected.value)
        assertEquals(spec(), PrivilegeUserServiceContract.specFrom(manager.peekRequests.single()))
        connection.unbind()
        assertFalse(connection.isConnected.value)
        assertEquals(0, manager.binder.deathRecipientCount)
        assertEquals(listOf("peek", "unbind"), manager.calls)
        assertEquals(listOf("peek-connection"), manager.unbindIds)
        assertEquals(2, manager.acknowledgedOperationIds.size)
    }

    @Test
    fun peekPropagatesConnectionFailureInsteadOfReturningNull() {
        val manager = RecordingManager().apply {
            peekResult = { PrivilegeUserServiceContract.errorBundle(DeadObjectException("service died")) }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        assertThrows(DeadObjectException::class.java) {
            runBlocking { client.peek(spec()) }
        }
        assertEquals(1, manager.cancelledOperationIds.size)
    }

    @Test
    fun peekCancellationCancelsPendingManagerOperation() = runBlocking {
        val manager = RecordingManager().apply { completePeek = false }
        val client = PrivilegeUserServiceClient { manager.binder }
        val job = launch { client.peek(spec()) }
        val operationId = manager.peekCalled.await()

        job.cancelAndJoin()

        assertEquals(listOf(operationId), manager.cancelledOperationIds)
        assertEquals(emptyList<String>(), manager.acknowledgedOperationIds)
        assertEquals(0, manager.binder.deathRecipientCount)
    }

    @Test
    fun remoteEndpointDeathReachesSuspendFallback() = runBlocking {
        val manager = RecordingManager().apply {
            bindResult = {
                PrivilegeUserServiceContract.errorBundle(DeadObjectException("service died"))
            }
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val failure = priv.kit.core.binder.PrivilegeBinderCall.orElse(
            fallback = { it },
        ) {
            client.bind(spec())
            error("Expected endpoint death")
        }
        assertTrue(failure is priv.kit.core.binder.PrivilegeBinderCallFailure.BinderDied)
        assertEquals("service died", failure.exception.message)
    }

    @Test
    fun bindUnbindAndStopUseManagerProtocol() = runBlocking {
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

        val connection = client.bind(spec, daemon = true)
        connection.unbind()
        client.stop(spec)

        assertEquals(serviceBinder.interfaceDescriptor, connection.binder.interfaceDescriptor)
        assertEquals(
            listOf("bind", "unbind", "stop"),
            manager.calls,
        )
        assertEquals(spec, PrivilegeUserServiceContract.specFrom(manager.bindRequests.single()))
        assertEquals(spec, PrivilegeUserServiceContract.specFrom(manager.stopRequests.single()))
        assertEquals(listOf("connection-1"), manager.unbindIds)
        assertNotNull(manager.bindClients.single())
        assertEquals(listOf(true), manager.bindDaemonValues)
        assertEquals(3, manager.acknowledgedOperationIds.size)
        assertEquals(emptyList<String>(), manager.cancelledOperationIds)
    }

    @Test
    fun errorBundleMapsToUserServiceException() {
        val manager = RecordingManager().apply {
            bindResult = { PrivilegeUserServiceContract.errorBundle("broken") }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(IllegalStateException::class.java) {
            runBlocking { client.bind(spec()) }
        }

        assertEquals("broken", throwable.message)
        assertEquals(1, manager.cancelledOperationIds.size)
    }

    @Test
    fun missingResultDataMapsToUserServiceException() {
        val manager = RecordingManager().apply {
            bindResult = { null }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(IllegalStateException::class.java) {
            runBlocking { client.bind(spec()) }
        }

        assertEquals("UserService response is missing result data", throwable.message)
        assertEquals(1, manager.cancelledOperationIds.size)
    }

    @Test
    fun cancellationCancelsPendingManagerOperation() = runBlocking {
        val manager = RecordingManager().apply {
            completeBind = false
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val job = launch {
            client.bind(spec())
        }

        val operationId = manager.bindCalled.await()
        job.cancelAndJoin()

        assertEquals(listOf(operationId), manager.cancelledOperationIds)
        assertEquals(emptyList<String>(), manager.acknowledgedOperationIds)
        assertEquals(0, manager.binder.deathRecipientCount)
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
            completeBind = false
        }
        val client = PrivilegeUserServiceClient { manager.binder }
        val failure = async {
            runCatching { client.bind(spec()) }.exceptionOrNull()
        }

        manager.bindCalled.await()
        manager.binder.killBinder(notifyDeathRecipients = true)

        val throwable = withTimeout(5_000L) { failure.await() }
        assertTrue(throwable is PrivilegeUnavailableException)
    }

    @Test
    fun managerProviderServerUnavailablePropagates() {
        val providerFailure = PrivilegeUnavailableException()
        val providerException = assertThrows(PrivilegeUnavailableException::class.java) {
            runBlocking {
                PrivilegeUserServiceClient { throw providerFailure }.bind(spec())
            }
        }
        assertEquals(providerFailure.message, providerException.message)
    }

    @Test
    fun managerDeadObjectExceptionBecomesUnavailable() {
        val deadObjectException = DeadObjectException("server died")
        val manager = RecordingManager().apply {
            bindResult = { throw deadObjectException }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(PrivilegeUnavailableException::class.java) {
            runBlocking { client.bind(spec()) }
        }

        assertSame(deadObjectException, generateSequence<Throwable>(throwable) { it.cause }.last())
    }

    @Test
    fun managerRemoteExceptionPropagatesWithoutMisclassifyingServerState() {
        val remoteException = RemoteException("remote exploded")
        val manager = RecordingManager().apply {
            bindResult = { throw remoteException }
        }
        val client = PrivilegeUserServiceClient { manager.binder }

        val throwable = assertThrows(RemoteException::class.java) {
            runBlocking { client.bind(spec()) }
        }

        assertSame(remoteException, throwable)
    }

    @Test
    fun bindRequiresConnectionIdAndServiceBinder() {
        val firstManager = RecordingManager().apply {
            bindResult = { PrivilegeUserServiceContract.successBundle() }
        }
        assertThrows(IllegalStateException::class.java) {
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
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PrivilegeUserServiceClient { secondManager.binder }.bind(spec())
            }
        }
        assertEquals(1, firstManager.cancelledOperationIds.size)
        assertEquals(1, secondManager.cancelledOperationIds.size)
        assertEquals(0, firstManager.binder.deathRecipientCount)
        assertEquals(0, secondManager.binder.deathRecipientCount)
    }

    @Test
    fun disconnectNotificationBeforeBindResponseIsNotLost() = runBlocking {
        val manager = RecordingManager().apply { disconnectBeforeResponse = true }
        val connection = PrivilegeUserServiceClient { manager.binder }.bind(spec())

        assertFalse(connection.isConnected.value)
        assertEquals(0, manager.binder.deathRecipientCount)
        connection.unbind()
        assertTrue(manager.unbindIds.isEmpty())
    }

    @Test
    fun lateNotificationForOldConnectionDoesNotDisconnectNewBinding() = runBlocking {
        val manager = RecordingManager()
        val client = PrivilegeUserServiceClient { manager.binder }
        val old = client.bind(spec())
        val oldReceiver = manager.disconnectReceivers.single()
        old.unbind()
        val current = client.bind(spec())

        oldReceiver.send(0, null)

        assertFalse(old.isConnected.value)
        assertTrue(current.isConnected.value)
        assertEquals(1, manager.binder.deathRecipientCount)
        current.unbind()
        assertEquals(0, manager.binder.deathRecipientCount)
    }

    @Test
    fun serverDeathDisconnectsAllBindingsAndUnbindIsIdempotent() = runBlocking {
        val manager = RecordingManager()
        val client = PrivilegeUserServiceClient { manager.binder }
        val first = client.bind(spec())
        val second = client.bind(spec())
        assertEquals(2, manager.binder.deathRecipientCount)

        manager.binder.killBinder(notifyDeathRecipients = true)

        assertFalse(first.isConnected.value)
        assertFalse(second.isConnected.value)
        first.unbind()
        second.unbind()
        assertTrue(manager.unbindIds.isEmpty())
        assertEquals(0, manager.binder.deathRecipientCount)
    }

    private class RecordingManager : IPrivilegeUserServiceManager {
        val binder = TestBinder(localInterface = this)
        val calls = mutableListOf<String>()
        val bindRequests = mutableListOf<Bundle>()
        val bindDaemonValues = mutableListOf<Boolean>()
        val peekRequests = mutableListOf<Bundle>()
        val stopRequests = mutableListOf<Bundle>()
        val unbindIds = mutableListOf<String>()
        val bindClients = mutableListOf<IBinder?>()
        val disconnectReceivers = mutableListOf<ResultReceiver>()
        val stopClients = mutableListOf<IBinder?>()
        val cancelledOperationIds = mutableListOf<String>()
        val acknowledgedOperationIds = mutableListOf<String>()
        val bindCalled = CompletableDeferred<String>()
        val stopCalled = CompletableDeferred<String>()
        val peekCalled = CompletableDeferred<String>()

        var completeBind: Boolean = true
        var completeStop: Boolean = true
        var completePeek: Boolean = true
        var disconnectBeforeResponse: Boolean = false
        var peekResult: () -> Bundle = { PrivilegeUserServiceContract.successBundle() }
        var bindResult: () -> Bundle? = {
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = "connection",
                binder = TestBinder(),
            )
        }

        override fun asBinder(): IBinder = binder

        override fun bindUserService(
            operationId: String,
            request: Bundle,
            daemon: Boolean,
            client: IBinder?,
            disconnectedReceiver: ResultReceiver?,
            receiver: ResultReceiver?,
        ) {
            calls += "bind"
            bindRequests += request
            bindClients += client
            bindDaemonValues += daemon
            disconnectReceivers += requireNotNull(disconnectedReceiver)
            if (disconnectBeforeResponse) disconnectedReceiver.send(0, null)
            bindCalled.complete(operationId)
            if (completeBind) {
                receiver?.send(0, bindResult())
            }
        }

        override fun cancelUserServiceOperation(operationId: String) {
            cancelledOperationIds += operationId
        }

        override fun peekUserService(
            operationId: String,
            request: Bundle,
            client: IBinder?,
            disconnectedReceiver: ResultReceiver?,
            receiver: ResultReceiver?,
        ) {
            calls += "peek"
            peekRequests += request
            disconnectReceivers += requireNotNull(disconnectedReceiver)
            peekCalled.complete(operationId)
            if (completePeek) {
                receiver?.send(0, peekResult())
            }
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
