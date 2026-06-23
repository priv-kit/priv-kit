package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbActionsTest {
    @Test
    fun pairingCheckStatusIsUnknownWithoutConnectPort() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiWirelessPairingCheckStatus(null),
        )
    }

    @Test
    fun pairingCheckStatusUsesCheckedResultWhenConnectPortExists() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.ON,
            privilegeUiWirelessPairingCheckStatus(true),
        )
        assertEquals(
            PrivilegeUiWirelessAdbStatus.OFF,
            privilegeUiWirelessPairingCheckStatus(false),
        )
    }
}
