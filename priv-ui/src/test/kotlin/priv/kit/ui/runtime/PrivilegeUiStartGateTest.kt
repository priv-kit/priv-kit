package priv.kit.ui.runtime

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiStartGateTest {
    @Test
    fun onlyInteractiveOwnerCanInteractWhileItsLeaseIsHeld() {
        val firstOwner = PrivilegeUiStartGate.newInteractiveOwner()
        val secondOwner = PrivilegeUiStartGate.newInteractiveOwner()

        assertTrue(firstOwner.canInteract(PrivilegeUiStartGate.state.value))
        assertTrue(secondOwner.canInteract(PrivilegeUiStartGate.state.value))

        val permit = firstOwner.tryAcquire()!!
        try {
            val acquiredState = PrivilegeUiStartGate.state.value
            assertTrue(firstOwner.canInteract(acquiredState))
            assertFalse(secondOwner.canInteract(acquiredState))
        } finally {
            permit.close()
        }

        assertTrue(secondOwner.canInteract(PrivilegeUiStartGate.state.value))
    }

    @Test
    fun silentAndInteractiveOwnersAreMutuallyExclusive() {
        val acquireInteractive = PrivilegeUiStartGate.newInteractivePermitAcquirer()
        val acquireOtherInteractive = PrivilegeUiStartGate.newInteractivePermitAcquirer()
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        assertEquals(
            PrivilegeUiStartGateOwner.SILENT,
            PrivilegeUiStartGate.state.value.owner,
        )
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        assertNull(acquireInteractive())

        silentPermit!!.close()

        val interactivePermit = acquireInteractive()
        assertNotNull(interactivePermit)
        assertEquals(
            PrivilegeUiStartGateOwner.INTERACTIVE,
            PrivilegeUiStartGate.state.value.owner,
        )
        val nestedInteractivePermit = acquireInteractive()
        assertNotNull(nestedInteractivePermit)
        assertEquals(2, PrivilegeUiStartGate.state.value.interactiveLeaseCount)
        assertNull(acquireOtherInteractive())
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        interactivePermit!!.close()
        assertEquals(
            PrivilegeUiStartGateOwner.INTERACTIVE,
            PrivilegeUiStartGate.state.value.owner,
        )
        assertEquals(1, PrivilegeUiStartGate.state.value.interactiveLeaseCount)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        nestedInteractivePermit!!.close()
        assertNull(PrivilegeUiStartGate.state.value.owner)
    }

    @Test
    fun closingPermitMoreThanOnceDoesNotReleaseAnotherOwner() {
        val acquireInteractive = PrivilegeUiStartGate.newInteractivePermitAcquirer()
        val firstPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        firstPermit.close()
        val secondPermit = acquireInteractive()!!

        firstPermit.close()

        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        secondPermit.close()
    }

    @Test
    fun onlySilentReleaseAdvancesCompletionSerial() {
        val initialSerial = PrivilegeUiStartGate.state.value.silentCompletionSerial

        PrivilegeUiStartGate.newInteractivePermitAcquirer().invoke()!!.close()
        assertEquals(initialSerial, PrivilegeUiStartGate.state.value.silentCompletionSerial)

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        silentPermit.close()
        silentPermit.close()
        assertEquals(
            initialSerial + 1L,
            PrivilegeUiStartGate.state.value.silentCompletionSerial,
        )
    }

    @Test
    fun claimedConnectionWaitsForJobAndConnectionHandlingInEitherOrder() {
        val firstCloseCount = AtomicInteger(0)
        val first = PrivilegeUiStartPermitLease(
            AutoCloseable { firstCloseCount.incrementAndGet() },
        )
        first.markConnectionClaimed()
        first.markJobCompleted(noConnectionCanBeClaimed = true)
        assertEquals(0, firstCloseCount.get())
        first.markConnectionHandled()
        assertEquals(1, firstCloseCount.get())

        val secondCloseCount = AtomicInteger(0)
        val second = PrivilegeUiStartPermitLease(
            AutoCloseable { secondCloseCount.incrementAndGet() },
        )
        second.markConnectionClaimed()
        second.markConnectionHandled()
        assertEquals(0, secondCloseCount.get())
        second.markJobCompleted(noConnectionCanBeClaimed = false)
        assertEquals(1, secondCloseCount.get())
    }

    @Test
    fun unclaimedConnectionReleasesOnlyAfterSessionCannotClaimIt() {
        val closeCount = AtomicInteger(0)
        val lease = PrivilegeUiStartPermitLease(
            AutoCloseable { closeCount.incrementAndGet() },
        )

        lease.markJobCompleted(noConnectionCanBeClaimed = false)
        assertEquals(0, closeCount.get())

        lease.markJobCompleted(noConnectionCanBeClaimed = true)
        assertEquals(1, closeCount.get())
        lease.releaseNow()
        assertEquals(1, closeCount.get())
    }

    @Test
    fun ownerCleanupKeepsPermitUntilAsynchronousTeardownCompletes() {
        val closeCount = AtomicInteger(0)
        val lease = PrivilegeUiStartPermitLease(
            AutoCloseable { closeCount.incrementAndGet() },
        )

        lease.markOwnerCleanupRequired()
        lease.markJobCompleted(noConnectionCanBeClaimed = true)

        assertEquals(0, closeCount.get())
        lease.markOwnerCleanupCompleted()
        assertEquals(1, closeCount.get())
    }
}
