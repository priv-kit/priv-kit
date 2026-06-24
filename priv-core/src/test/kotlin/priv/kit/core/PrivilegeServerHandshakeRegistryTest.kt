package priv.kit.core

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

class PrivilegeServerHandshakeRegistryTest {
    @Test
    fun readyHandshakeCanBePreparedAfterDelivery() {
        val token = newToken()
        val binder = fakeBinder()
        val serverInfo = serverInfo()

        assertTrue(
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = binder,
                serverInfo = serverInfo,
            ),
        )

        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        val result = pendingHandshake.await(1)

        assertEquals(token, result.token)
        assertEquals(serverInfo, result.serverInfo)
    }

    @Test
    fun readyListenerReceivesUnpreparedHandshake() {
        val token = newToken()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
        }

        try {
            assertTrue(
                PrivilegeServerHandshakeRegistry.deliverReady(
                    token = token,
                    serverBinder = fakeBinder(),
                    serverInfo = serverInfo(),
                ),
            )

            assertNotNull(received.get())
            assertEquals(token, received.get()?.token)
            assertNull(PrivilegeServerHandshakeRegistry.claimReady(token))
        } finally {
            listener.close()
        }
    }

    @Test
    fun regularDeliverStillRequiresPendingHandshake() {
        assertFalse(
            PrivilegeServerHandshakeRegistry.deliver(
                token = newToken(),
                serverBinder = fakeBinder(),
                serverInfo = serverInfo(),
            ),
        )
    }

    private fun newToken(): String =
        "token-${System.nanoTime()}"

    private fun serverInfo(): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = 2000,
            pid = 1234,
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
