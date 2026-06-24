package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbActionsTest {
    @Test
    fun passivePairingCheckStatusIsUnknownWithoutWirelessDebugging() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiPassivePairingCheckStatus(
                wirelessDebuggingOn = false,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun passivePairingCheckStatusPreservesExplicitPairingSuccess() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.ON,
            privilegeUiPassivePairingCheckStatus(
                wirelessDebuggingOn = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun passivePairingCheckStatusDoesNotInventPairingFailure() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiPassivePairingCheckStatus(
                wirelessDebuggingOn = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
            ),
        )
    }
}
