package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import priv.kit.ui.adb.PrivilegeUiStaticTcpConfirmationController
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchAction
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchConsent
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchDecision
import priv.kit.ui.adb.toDecision

class PrivilegeUiAdbTcpConfirmationTest {
    @Test
    fun unapprovedStaticTcpSwitchRequestsConfirmation() {
        assertEquals(
            PrivilegeUiStaticTcpSwitchDecision.REQUEST_CONFIRMATION,
            PrivilegeUiStaticTcpSwitchConsent.REQUEST_CONFIRMATION.toDecision(),
        )
    }

    @Test
    fun approvedStaticTcpSwitchCanProceedOnce() {
        assertEquals(
            PrivilegeUiStaticTcpSwitchDecision.SWITCH,
            PrivilegeUiStaticTcpSwitchConsent.APPROVED.toDecision(),
        )
    }

    @Test
    fun fallbackStaticTcpAttemptNeverSwitchesOrPrompts() {
        assertEquals(
            PrivilegeUiStaticTcpSwitchDecision.SKIP,
            PrivilegeUiStaticTcpSwitchConsent.DO_NOT_SWITCH.toDecision(),
        )
    }

    @Test
    fun startRequestTakesPriorityAndConfirmationIsOneShot() {
        val controller = PrivilegeUiStaticTcpConfirmationController()

        controller.request(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT)
        controller.request(PrivilegeUiStaticTcpSwitchAction.START_SERVICE)
        controller.request(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT)

        assertEquals(
            PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            controller.pendingAction.value,
        )
        assertEquals(
            PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            controller.take(),
        )
        assertNull(controller.take())
        assertNull(controller.pendingAction.value)
    }

    @Test
    fun cancellingDropsPendingSwitchWithoutApproval() {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        controller.request(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT)

        controller.cancel()

        assertNull(controller.pendingAction.value)
        assertNull(controller.take())
    }
}
