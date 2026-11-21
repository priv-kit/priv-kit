package priv.kit.ui.m3

import priv.kit.ui.m3.resources.*
import priv.kit.ui.*
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.m3.component.*
import kotlin.test.*

class PrivilegeM3DisplayPolicyTest {
    @Test
    fun actionLabelsDistinguishCancelAndCancelling() {
        assertEquals(
            Res.string.priv_ui_start_cancel_action,
            privilegeUiStartActionLabel(
                action = PrivilegeUiStartAction.CANCEL,
                startLabel = Res.string.priv_ui_root_authorization_action,
            ),
        )
        assertEquals(
            Res.string.priv_ui_start_cancelling_action,
            privilegeUiStartActionLabel(
                action = PrivilegeUiStartAction.CANCELLING,
                startLabel = Res.string.priv_ui_root_authorization_action,
            ),
        )
    }

    @Test
    fun autoRecoveryWarningRequiresInteractions() {
        val state = PrivilegeUiScreenState(
            desiredEnabled = true,
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
        )

        assertTrue(
            privilegeUiAutoRecoveryWarningVisible(
                state = state,
                interactionEnabled = true,
            ),
        )
        assertFalse(
            privilegeUiAutoRecoveryWarningVisible(
                state = state,
                interactionEnabled = false,
            ),
        )
    }
}
