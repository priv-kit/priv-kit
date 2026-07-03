package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbActionsTest {
    @Test
    fun pairingCheckStatusIsUnknownWithoutWirelessDebugging() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiPairingCheckStatus(
                wirelessDebuggingOn = false,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun pairingCheckStatusUsesSuccessfulRefreshResult() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.ON,
            privilegeUiPairingCheckStatus(
                wirelessDebuggingOn = true,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun pairingCheckStatusUsesFailedRefreshResult() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.OFF,
            privilegeUiPairingCheckStatus(
                wirelessDebuggingOn = true,
                pairingCheckPaired = false,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun pairingCheckStatusPreservesExplicitPairingSuccessWithoutRefreshResult() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.ON,
            privilegeUiPairingCheckStatus(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun pairingCheckStatusDoesNotInventPairingFailureWithoutRefreshResult() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiPairingCheckStatus(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
            ),
        )
    }
}
