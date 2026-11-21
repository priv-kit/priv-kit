package priv.kit.ui


import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.component.privilegeUiStartActionEnabled
import priv.kit.ui.component.startActionFor
import priv.kit.ui.component.startActionEnabled

class PrivilegeUiStartActionTest {
    @Test
    fun idleOwnerOffersStartAndRunningOwnerOffersCancel() {
        val idle = PrivilegeUiScreenState()
        val running = idle.copy(
            busy = true,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
        )

        assertEquals(
            PrivilegeUiStartAction.START,
            idle.startActionFor(
                source = PrivilegeUiRuntimeStartSource.ROOT,
                providerId = null,
            ),
        )
        assertEquals(
            PrivilegeUiStartAction.CANCEL,
            running.startActionFor(
                source = PrivilegeUiRuntimeStartSource.ROOT,
                providerId = null,
            ),
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
        val state = PrivilegeUiScreenState(
            busy = true,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
        )

        assertEquals(
            PrivilegeUiStartAction.CANCELLING,
            state.startActionFor(
                source = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
                providerId = null,
            ),
        )
        assertFalse(
            privilegeUiStartActionEnabled(
                action = PrivilegeUiStartAction.CANCELLING,
                startEnabled = true,
            ),
        )
        assertEquals(
            PrivilegeUiStartAction.NONE,
            state.startActionFor(
                source = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
                providerId = null,
            ),
        )
    }

    @Test
    fun externalOwnershipIncludesProviderId() {
        val state = PrivilegeUiScreenState(
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
            state.startActionFor(
                source = PrivilegeUiRuntimeStartSource.EXTERNAL,
                providerId = null,
            ),
        )
    }



    @Test
    fun connectedRuntimeEnablesIdleChildStartAction() {
        val state = PrivilegeUiScreenState(
            runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
        )
        val action = state.startActionFor(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
        )

        assertEquals(PrivilegeUiStartAction.START, action)
        assertTrue(
            state.startActionEnabled(
                action = action,
                startAvailable = true,
            ),
        )
    }
}
