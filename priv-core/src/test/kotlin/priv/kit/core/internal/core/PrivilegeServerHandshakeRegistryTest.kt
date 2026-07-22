package priv.kit.core.internal.core

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionOrigin
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PrivilegeServerHandshakeRegistryTest {
    @Test
    fun acceptedInitialLaunchIsReportedOnce(): Unit = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token, initialLaunchId)
        val acceptedOrigin = async(start = CoroutineStart.UNDISPATCHED) {
            PrivilegeRuntimeStartCoordinator.serverHandshakeAcceptedEvents.first()
        }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    initialLaunchId = initialLaunchId,
                ),
            )
            assertFalse(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 5678),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    initialLaunchId = initialLaunchId,
                ),
            )

            assertEquals(
                PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH,
                acceptedOrigin.await(),
            )
            pendingHandshake.await(1)
        } finally {
            PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)
        }
    }

    @Test
    fun readyHandshakeCanBePreparedAfterDelivery() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val binder = fakeBinder()
        val serverInfo = serverInfo()

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = binder,
                serverInfo = serverInfo,
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )

        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )
        val result = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)

        assertEquals(serverInfo, result.serverInfo)
        assertEquals(PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH, result.origin)
        assertEquals(initialLaunchId, result.initialLaunchId)
        assertNull(result.clientStartOperationId)
    }

    @Test
    fun readyListenerReceivesUnpreparedHandshake() = runBlocking {
        val token = newToken()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    initialLaunchId = null,
                ),
            )

            assertNotNull(received.get())
            assertEquals(PrivilegeServerHandshakeOrigin.OWNER_RECONNECT, received.get()?.origin)
            assertNull(received.get()?.initialLaunchId)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    @Test
    fun readyHandshakeIsCachedWhenListenerCannotInstallIt() = runBlocking {
        val token = newToken()
        val binder = fakeBinder()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { false }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = binder,
                    serverInfo = serverInfo(),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    initialLaunchId = null,
                ),
            )

            assertSame(binder, PrivilegeServerHandshakeRegistry.claimReady(token)?.serverBinder)
        } finally {
            listener.close()
        }
    }

    @Test
    fun successfulNewerDeliveryClearsAnOlderFailedReadyHandoff() = runBlocking {
        val token = newToken()
        var installReady = false
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { installReady }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    initialLaunchId = null,
                ),
            )
            installReady = true
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 5678),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    initialLaunchId = null,
                ),
            )

            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    @Test
    fun ownerReconnectDoesNotCompletePendingClientStart() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val reconnectBinder = fakeBinder()
        val initialLaunchBinder = fakeBinder()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = reconnectBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )
        val reconnect = PrivilegeServerHandshakeRegistry.claimReady(token)

        assertNotNull(reconnect)
        assertSame(reconnectBinder, reconnect?.serverBinder)
        assertEquals(PrivilegeServerHandshakeOrigin.OWNER_RECONNECT, reconnect?.origin)

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = initialLaunchBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )
        val initialLaunch = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)

        assertSame(initialLaunchBinder, initialLaunch.serverBinder)
        assertEquals(5678, initialLaunch.serverInfo.pid)
        assertEquals(PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH, initialLaunch.origin)
        assertEquals(initialLaunchId, initialLaunch.initialLaunchId)
    }

    @Test
    fun cachedOwnerReconnectIsNotConsumedByLaterPendingClientStart() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val reconnectBinder = fakeBinder()

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = reconnectBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )

        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )
        val reconnect = PrivilegeServerHandshakeRegistry.claimReady(token)

        assertNotNull(reconnect)
        assertSame(reconnectBinder, reconnect?.serverBinder)

        val initialLaunchBinder = fakeBinder()
        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = initialLaunchBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )

        val initialLaunch = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)

        assertSame(initialLaunchBinder, initialLaunch.serverBinder)
    }

    @Test
    fun initialLaunchWithDifferentIdDoesNotCompletePendingHandshake() = runBlocking {
        val token = newToken()
        val launchIdSuffix = System.nanoTime()
        val expectedLaunchId = "launch-expected-$launchIdSuffix"
        val differentLaunchId = "launch-different-$launchIdSuffix"
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = expectedLaunchId,
        )
        val differentBinder = fakeBinder()

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = differentBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = differentLaunchId,
            ),
        )

        val expectedBinder = fakeBinder()
        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = expectedBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = expectedLaunchId,
            ),
        )

        val expected = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(expectedLaunchId)

        assertSame(expectedBinder, expected.serverBinder)
        val unmatched = PrivilegeServerHandshakeRegistry.claimReady(token)
        assertSame(differentBinder, unmatched?.serverBinder)
        assertEquals(differentLaunchId, unmatched?.initialLaunchId)
    }

    @Test
    fun duplicateDeliveryForPendingLaunchIsRejected() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val firstBinder = fakeBinder()
        val duplicateBinder = fakeBinder()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = firstBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )
        assertFalse(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = duplicateBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )

        assertSame(firstBinder, pendingHandshake.await(1).serverBinder)
        PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)
    }

    @Test
    fun cancelAfterDeliveryForwardsUnacknowledgedResultToReadyListener() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val binder = fakeBinder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = binder,
                    serverInfo = serverInfo(),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    initialLaunchId = initialLaunchId,
                ),
            )
            assertSame(binder, pendingHandshake.await(1).serverBinder)
            assertNull(received.get())

            assertTrue(PrivilegeServerHandshakeRegistry.cancel(initialLaunchId))

            assertSame(binder, received.get()?.serverBinder)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    @Test
    fun acknowledgeAfterDeliveryPreventsCancelFromForwardingResult() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    initialLaunchId = initialLaunchId,
                ),
            )
            pendingHandshake.await(1)

            PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)
            assertFalse(PrivilegeServerHandshakeRegistry.cancel(initialLaunchId))

            assertNull(received.get())
            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    @Test
    fun cancelBeforeDeliveryAllowsLaterResultToReachReadyListener() = runBlocking {
        val token = newToken()
        val initialLaunchId = newLaunchId()
        val binder = fakeBinder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        PrivilegeServerHandshakeRegistry.prepare(
            token = token,
            initialLaunchId = initialLaunchId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
            true
        }

        try {
            assertFalse(PrivilegeServerHandshakeRegistry.cancel(initialLaunchId))

            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = binder,
                    serverInfo = serverInfo(),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    initialLaunchId = initialLaunchId,
                ),
            )

            assertSame(binder, received.get()?.serverBinder)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    private fun newToken(): String =
        "token-${System.nanoTime()}"

    private fun newLaunchId(): String =
        "launch-${System.nanoTime()}"

    private fun serverInfo(pid: Int = 1234): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = 2000,
            pid = pid,
            protocolVersion = PrivilegeProtocol.VERSION,
        )

    private fun fakeBinder(): IBinder =
        Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Void.TYPE -> Unit
                else -> null
            }
        } as IBinder
}
