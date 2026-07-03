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

    @Test
    fun wirelessAdbStartOptionsEnableConfiguredTcpPortWhenPolicyAllowsTcp() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            tcpPort = 4567,
        )

        assertEquals(true, options.tcpMode)
        assertEquals(4567, options.tcpPort)
        assertEquals(true, options.discoverPort)
    }

    @Test
    fun wirelessAdbStartOptionsDoNotEnableTcpWhenPolicyDisablesTcp() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
        )

        assertEquals(false, options.tcpMode)
    }
}
