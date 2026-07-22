package priv.kit.ui.runtime

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

}
