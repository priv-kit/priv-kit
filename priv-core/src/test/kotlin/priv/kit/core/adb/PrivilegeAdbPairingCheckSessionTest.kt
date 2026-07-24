package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import javax.net.ssl.SSLProtocolException

class PrivilegeAdbPairingCheckSessionTest {
    @Test
    fun checkReusesPersistentConnectionUntilClosed() = runBlocking {
        val connections = mutableListOf<FakeAdbConnection>()
        val session = session(
            explicitPort = 37099,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                FakeAdbConnection().also(connections::add)
            },
        )

        val first = session.check()
        val second = session.check()

        assertTrue(first.paired)
        assertTrue(second.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.PAIRED, first.status)
        assertEquals(PrivilegeAdbPairingCheckStatus.PAIRED, second.status)
        assertEquals(1, connections.size)
        assertEquals(1, connections.single().checkAuthorizationCount)
        assertEquals(1, connections.single().keepAliveCount)
        assertEquals(0, connections.single().closeCount)

        session.close()

        assertEquals(1, connections.single().closeCount)
    }

    @Test
    fun checkReconnectsAfterPersistentConnectionFails() = runBlocking {
        val connections = mutableListOf<FakeAdbConnection>()
        val session = session(
            explicitPort = 37099,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                FakeAdbConnection().also(connections::add)
            },
        )

        assertTrue(session.check().paired)
        connections.single().failKeepAlive = true

        val result = session.check()

        assertTrue(result.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.PAIRED, result.status)
        assertEquals(2, connections.size)
        assertEquals(1, connections.first().closeCount)
        assertEquals(1, connections.last().checkAuthorizationCount)
        assertEquals(0, connections.last().keepAliveCount)
    }

    @Test
    fun checkReportsUnavailableWhenNoPortCanBeResolved() = runBlocking {
        val session = session(
            explicitPort = null,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                error("client should not be created without an ADB connect port")
            },
        )

        val result = session.check()

        assertFalse(result.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.UNAVAILABLE, result.status)
        assertNull(result.port)
        assertEquals("ADB connect port is not available", result.failureMessage)
    }

    @Test
    fun checkReportsUnpairedOnlyWhenAdbSaysUnauthorized() = runBlocking {
        val session = session(
            explicitPort = 37099,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                FakeAdbConnection(status = PrivilegeAdbAuthorizationStatus.UNAUTHORIZED)
            },
        )

        val result = session.check()

        assertFalse(result.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.UNPAIRED, result.status)
        assertEquals("ADB key is not authorized", result.failureMessage)
    }

    @Test
    fun checkReportsErrorWhenPairingProbeFails() = runBlocking {
        val session = session(
            explicitPort = 37099,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                FakeAdbConnection(failCheckAuthorization = true)
            },
        )

        val result = session.check()

        assertFalse(result.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.ERROR, result.status)
    }

    @Test
    fun checkReportsUnpairedWhenTlsRejectsUnknownCertificate() = runBlocking {
        val session = session(
            explicitPort = 37099,
            discoverPort = false,
            discoverConnectEndpoint = { error("discovery should not be used") },
            clientFactory = {
                FakeAdbConnection(
                    authorizationFailure = SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN"),
                )
            },
        )

        val result = session.check()

        assertFalse(result.paired)
        assertEquals(PrivilegeAdbPairingCheckStatus.UNPAIRED, result.status)
        assertEquals("ADB key is not authorized", result.failureMessage)
    }

    @Test
    fun checkPropagatesDiscoveryInterruptionAndRestoresInterruptFlag() = runBlocking {
        val session = session(
            explicitPort = null,
            discoverPort = true,
            discoverConnectEndpoint = { throw InterruptedException("cancelled") },
            clientFactory = { error("client should not be created after interruption") },
        )

        try {
            assertThrows(InterruptedException::class.java) { runBlocking { session.check() } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            session.close()
        }
    }

    private fun session(
        explicitPort: Int?,
        discoverPort: Boolean,
        discoverConnectEndpoint: (Long) -> PrivilegeAdbEndpoint,
        clientFactory: (PrivilegeAdbEndpoint) -> PrivilegeAdbAuthorizationConnection,
    ): PrivilegeAdbPairingCheckSession =
        PrivilegeAdbPairingCheckSession(
            identity = PrivilegeAdbIdentity.default(
                deviceName = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
            ),
            publicKeyFingerprint = "AA:BB",
            explicitPort = explicitPort,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = 1_000L,
            discoverConnectEndpoint = discoverConnectEndpoint,
            clientFactory = clientFactory,
        )

    private class FakeAdbConnection(
        private val status: PrivilegeAdbAuthorizationStatus = PrivilegeAdbAuthorizationStatus.AUTHORIZED,
        private val failCheckAuthorization: Boolean = false,
        private val authorizationFailure: Throwable? = null,
    ) : PrivilegeAdbAuthorizationConnection {
        var failKeepAlive = false
        var checkAuthorizationCount = 0
            private set
        var keepAliveCount = 0
            private set
        var closeCount = 0
            private set

        override fun connect(output: PrivilegeAdbOutput?) {
            checkAuthorization(output)
        }

        override fun checkAuthorization(output: PrivilegeAdbOutput?): PrivilegeAdbAuthorizationStatus {
            checkAuthorizationCount += 1
            authorizationFailure?.let { throw it }
            if (failCheckAuthorization) {
                error("pairing probe failed")
            }
            return status
        }

        override fun keepAlive(output: PrivilegeAdbOutput) {
            keepAliveCount += 1
            if (failKeepAlive) {
                error("keep alive failed")
            }
        }

        override fun close() {
            closeCount += 1
        }
    }
}
