package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.ui.component.PrivilegeUiServiceStatusAction
import priv.kit.ui.component.privilegeUiServiceStatusAction
import priv.kit.ui.component.privilegeUiServiceStatusActionEnabled

class PrivilegeUiServiceStatusTest {
    @Test
    fun runningStartOffersCancelAction() {
        val action = privilegeUiServiceStatusAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
        )

        assertEquals(PrivilegeUiServiceStatusAction.CANCEL, action)
        assertTrue(privilegeUiServiceStatusActionEnabled(action, busy = true))
    }

    @Test
    fun cancellingStartOffersDisabledCancellingAction() {
        val action = privilegeUiServiceStatusAction(
            runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
        )

        assertEquals(PrivilegeUiServiceStatusAction.CANCELLING, action)
        assertFalse(privilegeUiServiceStatusActionEnabled(action, busy = true))
    }

    @Test
    fun idleConnectedRuntimeOffersStopAndIdleRuntimeOffersStart() {
        assertEquals(
            PrivilegeUiServiceStatusAction.STOP,
            privilegeUiServiceStatusAction(
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            ),
        )
        assertEquals(
            PrivilegeUiServiceStatusAction.START,
            privilegeUiServiceStatusAction(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            ),
        )
        assertEquals(
            PrivilegeUiServiceStatusAction.START,
            privilegeUiServiceStatusAction(
                runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            ),
        )
        assertFalse(
            privilegeUiServiceStatusActionEnabled(
                action = PrivilegeUiServiceStatusAction.START,
                busy = true,
            ),
        )
    }
}
