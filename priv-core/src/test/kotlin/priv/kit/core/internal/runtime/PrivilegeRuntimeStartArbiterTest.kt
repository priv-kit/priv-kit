package priv.kit.core.internal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin

class PrivilegeRuntimeStartArbiterTest {
    @Test
    fun runtimeStartLeaseRejectsRepeatedClose() {
        val releasedOperationIds = mutableListOf<Long>()
        val lease = PrivilegeRuntimeStartLease(
            operationId = 7L,
            release = { operationId -> releasedOperationIds += operationId },
        )

        lease.close()
        val exception = assertThrows(IllegalStateException::class.java) {
            lease.close()
        }

        assertEquals(listOf(7L), releasedOperationIds)
        assertTrue(exception.message.orEmpty().contains("already closed"))
    }

    @Test
    fun preflightReportsRemainingOwnerReconnectGrace() {
        var nowMillis = 100L
        val arbiter = PrivilegeRuntimeStartArbiter { nowMillis }

        arbiter.markOwnerProcessStarted(graceMillis = 1_000L)
        nowMillis = 350L

        assertEquals(750L, arbiter.beginPreflight().remainingReconnectGraceMillis)

        nowMillis = 1_200L

        assertEquals(0L, arbiter.beginPreflight().remainingReconnectGraceMillis)
    }

    @Test
    fun ownerReconnectBeforeLaunchBarrierInvalidatesClientPreflight() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val preflight = arbiter.beginPreflight()
        val reconnectTicket = arbiter.tryAcceptHandshake(
            origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
            initialLaunchId = null,
        )

        assertNotNull(reconnectTicket)
        assertNull(arbiter.tryCommitClientStart(preflight))

        arbiter.finishHandshake(requireNotNull(reconnectTicket))

        val refreshedPreflight = arbiter.beginPreflight()
        assertNotNull(arbiter.tryCommitClientStart(refreshedPreflight))
    }

    @Test
    fun ownerReconnectAfterLaunchBarrierIsRejected() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val operationId = requireNotNull(
            arbiter.tryCommitClientStart(arbiter.beginPreflight()),
        )
        val initialLaunchId = "launch-1"
        assertTrue(arbiter.beginClientLaunch(operationId, initialLaunchId))

        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )
        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = "different-launch",
            ),
        )

        val initialLaunchTicket = arbiter.tryAcceptHandshake(
            origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
            initialLaunchId = initialLaunchId,
        )
        assertNotNull(initialLaunchTicket)
        assertEquals(operationId, initialLaunchTicket?.clientStartOperationId)

        arbiter.finishHandshake(requireNotNull(initialLaunchTicket))
        arbiter.finishClientStart(operationId)

        val reconnectTicket = arbiter.tryAcceptHandshake(
            origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
            initialLaunchId = null,
        )
        assertNotNull(reconnectTicket)
        arbiter.finishHandshake(requireNotNull(reconnectTicket))
    }

    @Test
    fun ownerProcessSignalInvalidatesEarlierPreflight() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val preflight = arbiter.beginPreflight()

        arbiter.markOwnerProcessStarted(graceMillis = 1_000L)

        assertNull(arbiter.tryCommitClientStart(preflight))
        assertTrue(arbiter.beginPreflight().remainingReconnectGraceMillis > 0L)
    }

    @Test
    fun onlyOneClientStartCanCommitAndAReleasedLeaseRequiresFreshPreflight() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val firstPreflight = arbiter.beginPreflight()
        val competingPreflight = arbiter.beginPreflight()

        val operationId = requireNotNull(arbiter.tryCommitClientStart(firstPreflight))

        assertNull(arbiter.tryCommitClientStart(competingPreflight))

        arbiter.finishClientStart(operationId)

        assertNull(arbiter.tryCommitClientStart(competingPreflight))
        assertNotNull(arbiter.tryCommitClientStart(arbiter.beginPreflight()))
    }

    @Test
    fun liveServerRejectsClientCommitAndAllHandshakesUntilDisconnected() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        arbiter.markServerConnected()

        assertNull(arbiter.tryCommitClientStart(arbiter.beginPreflight()))
        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )
        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = "launch-while-connected",
            ),
        )

        arbiter.markServerDisconnected()

        val operationId = arbiter.tryCommitClientStart(arbiter.beginPreflight())
        assertNotNull(operationId)
        arbiter.finishClientStart(requireNotNull(operationId))
    }

    @Test
    fun deferredOwnerReconnectIsResignalledWhenClientFinishesWithoutServer() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val operationId = requireNotNull(
            arbiter.tryCommitClientStart(arbiter.beginPreflight()),
        )

        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )

        assertTrue(arbiter.finishClientStart(operationId))
    }

    @Test
    fun successfulClientConnectionSuppressesDeferredOwnerReconnectSignal() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val operationId = requireNotNull(
            arbiter.tryCommitClientStart(arbiter.beginPreflight()),
        )
        val initialLaunchId = "launch-success"
        assertTrue(arbiter.beginClientLaunch(operationId, initialLaunchId))
        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )
        val initialLaunchTicket = requireNotNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = initialLaunchId,
            ),
        )
        arbiter.finishHandshake(initialLaunchTicket)
        arbiter.markServerConnected()

        assertFalse(arbiter.finishClientStart(operationId))

        arbiter.markServerDisconnected()
        assertNotNull(arbiter.tryCommitClientStart(arbiter.beginPreflight()))
    }

    @Test
    fun onlyOneHandshakeCanBeInFlight() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val firstTicket = requireNotNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )

        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                initialLaunchId = null,
            ),
        )
        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = "launch-while-handshake-in-flight",
            ),
        )

        assertTrue(arbiter.finishHandshake(firstTicket))

        val nextTicket = arbiter.tryAcceptHandshake(
            origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
            initialLaunchId = "launch-after-handshake",
        )
        assertNotNull(nextTicket)
        arbiter.finishHandshake(requireNotNull(nextTicket))
    }

    @Test
    fun lateLaunchIdFromReleasedOperationIsRejectedByCurrentOperation() {
        val arbiter = PrivilegeRuntimeStartArbiter { 0L }
        val firstOperationId = requireNotNull(
            arbiter.tryCommitClientStart(arbiter.beginPreflight()),
        )
        assertTrue(arbiter.beginClientLaunch(firstOperationId, "launch-old"))
        arbiter.finishClientStart(firstOperationId)

        val currentOperationId = requireNotNull(
            arbiter.tryCommitClientStart(arbiter.beginPreflight()),
        )
        assertTrue(arbiter.beginClientLaunch(currentOperationId, "launch-current"))

        assertNull(
            arbiter.tryAcceptHandshake(
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                initialLaunchId = "launch-old",
            ),
        )
        val currentTicket = arbiter.tryAcceptHandshake(
            origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
            initialLaunchId = "launch-current",
        )

        assertNotNull(currentTicket)
        assertEquals(currentOperationId, currentTicket?.clientStartOperationId)
        arbiter.finishHandshake(requireNotNull(currentTicket))
        arbiter.finishClientStart(currentOperationId)
    }
}
