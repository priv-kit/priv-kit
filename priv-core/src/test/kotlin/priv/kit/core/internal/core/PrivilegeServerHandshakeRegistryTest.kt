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
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

class PrivilegeServerHandshakeRegistryTest {
    @Test
    fun readyHandshakeCanBePreparedAfterDelivery() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val binder = fakeBinder()
        val serverInfo = serverInfo(pid = 1234)

        assertTrue(
            deliverReady(
                serverBinder = binder,
                serverInfo = serverInfo,
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = launchCorrelationId,
            ),
        )

        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )
        val result = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)

        assertSame(serverInfo, result.serverInfo)
        assertEquals(PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH, result.origin)
        assertEquals(launchCorrelationId, result.launchCorrelationId)
        assertNull(result.clientStartOperationId)
    }

    @Test
    fun readyListenerReceivesUnpreparedHandshake() = runBlocking {
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                deliverReady(
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    launchCorrelationId = null,
                ),
            )

            assertNotNull(received.get())
            assertEquals(PrivilegeServerHandshakeOrigin.OWNER_RECONNECT, received.get()?.origin)
            assertNull(received.get()?.launchCorrelationId)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady())
        } finally {
            listener.close()
        }
    }

    @Test
    fun readyHandshakeIsCachedWhenListenerCannotInstallIt() = runBlocking {
        val binder = fakeBinder()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { false }

        try {
            assertTrue(
                deliverReady(
                    serverBinder = binder,
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    launchCorrelationId = null,
                ),
            )

            assertSame(binder, PrivilegeServerHandshakeRegistry.claimReady()?.serverBinder)
        } finally {
            listener.close()
        }
    }

    @Test
    fun successfulNewerDeliveryClearsAnOlderFailedReadyHandoff() = runBlocking {
        var installReady = false
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { installReady }

        try {
            assertTrue(
                deliverReady(
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    launchCorrelationId = null,
                ),
            )
            installReady = true
            assertTrue(
                deliverReady(
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 5678),
                    origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                    launchCorrelationId = null,
                ),
            )

            assertNull(PrivilegeServerHandshakeRegistry.claimReady())
        } finally {
            listener.close()
        }
    }

    @Test
    fun ownerReconnectDoesNotCompletePendingClientStart() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val reconnectBinder = fakeBinder()
        val initialLaunchBinder = fakeBinder()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )

        assertTrue(
            deliverReady(
                serverBinder = reconnectBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                launchCorrelationId = null,
            ),
        )
        val reconnect = PrivilegeServerHandshakeRegistry.claimReady()

        assertNotNull(reconnect)
        assertSame(reconnectBinder, reconnect?.serverBinder)
        assertEquals(PrivilegeServerHandshakeOrigin.OWNER_RECONNECT, reconnect?.origin)

        assertTrue(
            deliverReady(
                serverBinder = initialLaunchBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = launchCorrelationId,
            ),
        )
        val initialLaunch = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)

        assertSame(initialLaunchBinder, initialLaunch.serverBinder)
        assertEquals(5678, initialLaunch.serverInfo.pid)
        assertEquals(PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH, initialLaunch.origin)
        assertEquals(launchCorrelationId, initialLaunch.launchCorrelationId)
    }

    @Test
    fun cachedOwnerReconnectIsNotConsumedByLaterPendingClientStart() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val reconnectBinder = fakeBinder()

        assertTrue(
            deliverReady(
                serverBinder = reconnectBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                launchCorrelationId = null,
            ),
        )

        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )
        val reconnect = PrivilegeServerHandshakeRegistry.claimReady()

        assertNotNull(reconnect)
        assertSame(reconnectBinder, reconnect?.serverBinder)

        val initialLaunchBinder = fakeBinder()
        assertTrue(
            deliverReady(
                serverBinder = initialLaunchBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = launchCorrelationId,
            ),
        )

        val initialLaunch = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)

        assertSame(initialLaunchBinder, initialLaunch.serverBinder)
    }

    @Test
    fun initialLaunchWithDifferentCorrelationIdDoesNotCompletePendingHandshake() = runBlocking {
        val correlationIdSuffix = System.nanoTime()
        val expectedCorrelationId = "launch-expected-$correlationIdSuffix"
        val differentCorrelationId = "launch-different-$correlationIdSuffix"
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = expectedCorrelationId,
        )
        val differentBinder = fakeBinder()

        assertTrue(
            deliverReady(
                serverBinder = differentBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = differentCorrelationId,
            ),
        )

        val expectedBinder = fakeBinder()
        assertTrue(
            deliverReady(
                serverBinder = expectedBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = expectedCorrelationId,
            ),
        )

        val expected = pendingHandshake.await(1)
        PrivilegeServerHandshakeRegistry.acknowledge(expectedCorrelationId)

        assertSame(expectedBinder, expected.serverBinder)
        val unmatched = PrivilegeServerHandshakeRegistry.claimReady()
        assertSame(differentBinder, unmatched?.serverBinder)
        assertEquals(differentCorrelationId, unmatched?.launchCorrelationId)
    }

    @Test
    fun duplicateDeliveryForPendingLaunchIsRejected() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val firstBinder = fakeBinder()
        val duplicateBinder = fakeBinder()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )

        assertTrue(
            deliverReady(
                serverBinder = firstBinder,
                serverInfo = serverInfo(pid = 1234),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = launchCorrelationId,
            ),
        )
        assertFalse(
            deliverReady(
                serverBinder = duplicateBinder,
                serverInfo = serverInfo(pid = 5678),
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                launchCorrelationId = launchCorrelationId,
            ),
        )

        assertSame(firstBinder, pendingHandshake.await(1).serverBinder)
        PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)
    }

    @Test
    fun cancelAfterDeliveryForwardsUnacknowledgedResultToReadyListener() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val binder = fakeBinder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                deliverReady(
                    serverBinder = binder,
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    launchCorrelationId = launchCorrelationId,
                ),
            )
            assertSame(binder, pendingHandshake.await(1).serverBinder)
            assertNull(received.get())

            assertTrue(PrivilegeServerHandshakeRegistry.cancel(launchCorrelationId))

            assertSame(binder, received.get()?.serverBinder)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady())
        } finally {
            listener.close()
        }
    }

    @Test
    fun acknowledgeAfterDeliveryPreventsCancelFromForwardingResult() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            assertTrue(
                deliverReady(
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    launchCorrelationId = launchCorrelationId,
                ),
            )
            pendingHandshake.await(1)

            PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)
            assertFalse(PrivilegeServerHandshakeRegistry.cancel(launchCorrelationId))

            assertNull(received.get())
            assertNull(PrivilegeServerHandshakeRegistry.claimReady())
        } finally {
            listener.close()
        }
    }

    @Test
    fun cancelBeforeDeliveryAllowsLaterResultToReachReadyListener() = runBlocking {
        val launchCorrelationId = newCorrelationId()
        val binder = fakeBinder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        PrivilegeServerHandshakeRegistry.prepare(
            launchCorrelationId = launchCorrelationId,
        )
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            assertFalse(PrivilegeServerHandshakeRegistry.cancel(launchCorrelationId))

            assertTrue(
                deliverReady(
                    serverBinder = binder,
                    serverInfo = serverInfo(pid = 1234),
                    origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                    launchCorrelationId = launchCorrelationId,
                ),
            )

            assertSame(binder, received.get()?.serverBinder)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady())
        } finally {
            listener.close()
        }
    }

    private fun deliverReady(
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
        origin: PrivilegeServerHandshakeOrigin,
        launchCorrelationId: String?,
    ): Boolean =
        PrivilegeServerHandshakeRegistry.deliverReady(
            serverBinder = serverBinder,
            serviceEndpoints = PrivilegeServerServiceEndpoints(
                fileSystemBinder = fakeBinder(),
                userServiceManagerBinder = fakeBinder(),
            ),
            serverInfo = serverInfo,
            origin = origin,
            launchCorrelationId = launchCorrelationId,
        )

    private fun newCorrelationId(): String =
        "launch-${System.nanoTime()}"

    private fun serverInfo(pid: Int): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = 2000,
            pid = pid,
            protocolVersion = PrivilegeProtocol.VERSION,
            lifecycleBinder = android.os.Binder(),
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
