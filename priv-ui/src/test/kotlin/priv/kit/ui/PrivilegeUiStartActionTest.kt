package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.component.privilegeUiStartActionEnabled
import priv.kit.ui.component.privilegeUiStartActionLabel
import priv.kit.ui.component.startActionFor
import priv.kit.ui.component.startActionEnabled

class PrivilegeUiStartActionTest {
    @Test
    fun idleOwnerOffersStartAndRunningOwnerOffersCancel() {
        val idle = PrivilegeUiState()
        val running = idle.copy(
            busy = true,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
        )

        assertEquals(
            PrivilegeUiStartAction.START,
            idle.startActionFor(PrivilegeUiRuntimeStartSource.ROOT),
        )
        assertEquals(
            PrivilegeUiStartAction.CANCEL,
            running.startActionFor(PrivilegeUiRuntimeStartSource.ROOT),
        )
        assertTrue(
            privilegeUiStartActionEnabled(
                action = PrivilegeUiStartAction.CANCEL,
                startEnabled = false,
            ),
        )
    }

    @Test
    fun cancellingOwnerIsDisabledAndNonOwnerCannotAct() {
        val state = PrivilegeUiState(
            busy = true,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
        )

        assertEquals(
            PrivilegeUiStartAction.CANCELLING,
            state.startActionFor(PrivilegeUiRuntimeStartSource.ADB_WIRELESS),
        )
        assertFalse(
            privilegeUiStartActionEnabled(
                action = PrivilegeUiStartAction.CANCELLING,
                startEnabled = true,
            ),
        )
        assertEquals(
            PrivilegeUiStartAction.NONE,
            state.startActionFor(PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP),
        )
    }

    @Test
    fun externalOwnershipIncludesProviderId() {
        val state = PrivilegeUiState(
            busy = true,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
            runtimeStartProviderId = "owner",
        )

        assertEquals(
            PrivilegeUiStartAction.CANCEL,
            state.startActionFor(
                source = PrivilegeUiRuntimeStartSource.EXTERNAL,
                providerId = "owner",
            ),
        )
        assertEquals(
            PrivilegeUiStartAction.NONE,
            state.startActionFor(
                source = PrivilegeUiRuntimeStartSource.EXTERNAL,
                providerId = "other",
            ),
        )
        assertEquals(
            PrivilegeUiStartAction.NONE,
            state.startActionFor(PrivilegeUiRuntimeStartSource.EXTERNAL),
        )
    }

    @Test
    fun actionLabelsDistinguishCancelAndCancelling() {
        assertEquals(
            R.string.priv_ui_start_cancel_action,
            privilegeUiStartActionLabel(
                action = PrivilegeUiStartAction.CANCEL,
                startLabel = R.string.priv_ui_root_authorization_action,
            ),
        )
        assertEquals(
            R.string.priv_ui_start_cancelling_action,
            privilegeUiStartActionLabel(
                action = PrivilegeUiStartAction.CANCELLING,
                startLabel = R.string.priv_ui_root_authorization_action,
            ),
        )
    }

    @Test
    fun connectedRuntimeEnablesIdleChildStartAction() {
        val state = PrivilegeUiState(
            runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
        )
        val action = state.startActionFor(PrivilegeUiRuntimeStartSource.ROOT)

        assertEquals(PrivilegeUiStartAction.START, action)
        assertTrue(state.startActionEnabled(action))
    }
}
