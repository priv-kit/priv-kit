package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class PrivilegeAdbTcpAuthorizationCheckSessionTest {
    @Test
    fun checkReusesAuthorizedPersistentConnectionUntilClosed() {
        val connections = mutableListOf<FakeAdbAuthorizationConnection>()
        val session = session(
            clientFactory = {
                FakeAdbAuthorizationConnection().also(connections::add)
            },
        )

        val first = session.check()
        val second = session.check()

        assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, first.status)
        assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, second.status)
        assertEquals(1, connections.size)
        assertEquals(1, connections.single().authorizationCheckCount)
        assertEquals(1, connections.single().keepAliveCount)
        assertEquals(0, connections.single().closeCount)

        session.close()

        assertEquals(1, connections.single().closeCount)
    }

    @Test
    fun checkClosesUnauthorizedConnection() {
        val connections = mutableListOf<FakeAdbAuthorizationConnection>()
        val session = session(
            clientFactory = {
                FakeAdbAuthorizationConnection(
                    authorizationStatus = PrivilegeAdbAuthorizationStatus.UNAUTHORIZED,
                ).also(connections::add)
            },
        )

        val result = session.check()

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED, result.status)
        assertEquals(1, connections.size)
        assertEquals(1, connections.single().authorizationCheckCount)
        assertEquals(1, connections.single().closeCount)
    }

    @Test
    fun checkReconnectsAfterPersistentConnectionFails() {
        val connections = mutableListOf<FakeAdbAuthorizationConnection>()
        val session = session(
            clientFactory = {
                FakeAdbAuthorizationConnection().also(connections::add)
            },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, session.check().status)
        connections.single().failKeepAlive = true

        val result = session.check()

        assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, result.status)
        assertEquals(2, connections.size)
        assertEquals(1, connections.first().closeCount)
        assertEquals(1, connections.last().authorizationCheckCount)
        assertEquals(0, connections.last().keepAliveCount)
    }

    @Test
    fun checkMapsConnectionFailureToUnavailable() {
        val connections = mutableListOf<FakeAdbAuthorizationConnection>()
        val session = session(
            clientFactory = {
                FakeAdbAuthorizationConnection(
                    authorizationFailure = ConnectException("refused"),
                ).also(connections::add)
            },
        )

        val result = session.check()

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, result.status)
        assertEquals(1, connections.size)
        assertEquals(1, connections.single().closeCount)
        assertTrue(result.failureMessage.orEmpty().contains("ConnectException"))
    }

    private fun session(
        clientFactory: () -> PrivilegeAdbAuthorizationConnection,
    ): PrivilegeAdbTcpAuthorizationCheckSession =
        PrivilegeAdbTcpAuthorizationCheckSession(
            identity = PrivilegeAdbIdentity.default(),
            publicKeyFingerprint = "AA:BB",
            tcpPort = 5555,
            clientFactory = clientFactory,
        )

    private class FakeAdbAuthorizationConnection(
        private val authorizationStatus: PrivilegeAdbAuthorizationStatus = PrivilegeAdbAuthorizationStatus.AUTHORIZED,
        private val authorizationFailure: Throwable? = null,
    ) : PrivilegeAdbAuthorizationConnection {
        var failKeepAlive = false
        var connectCount = 0
            private set
        var authorizationCheckCount = 0
            private set
        var keepAliveCount = 0
            private set
        var closeCount = 0
            private set

        override fun connect(output: PrivilegeAdbOutput?) {
            connectCount += 1
        }

        override fun keepAlive(output: PrivilegeAdbOutput) {
            keepAliveCount += 1
            if (failKeepAlive) {
                error("keep alive failed")
            }
        }

        override fun checkAuthorization(output: PrivilegeAdbOutput?): PrivilegeAdbAuthorizationStatus {
            authorizationCheckCount += 1
            authorizationFailure?.let { throw it }
            return authorizationStatus
        }

        override fun close() {
            closeCount += 1
        }
    }
}
