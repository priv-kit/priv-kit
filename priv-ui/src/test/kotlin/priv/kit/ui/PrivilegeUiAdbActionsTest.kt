package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun refreshingPairingCheckStatusIsUnknownWhenWirelessDebuggingIsOff() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            privilegeUiRefreshingPairingCheckStatus(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
            ),
        )
    }

    @Test
    fun refreshingPairingCheckStatusChecksWhenWirelessDebuggingMayBeOn() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.CHECKING,
            privilegeUiRefreshingPairingCheckStatus(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
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
        assertEquals(true, options.disableWirelessDebuggingAfterStart)
    }

    @Test
    fun wirelessAdbStartOptionsUseActiveTcpPortWithoutRestartingIt() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            tcpPort = 4567,
            activeTcpPort = 5555,
        )

        assertEquals(5555, options.port)
        assertEquals(false, options.tcpMode)
        assertEquals(false, options.discoverPort)
        assertEquals(4567, options.tcpPort)
        assertEquals(priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER, options.wirelessDebuggingControl)
    }

    @Test
    fun wirelessAdbStartOptionsDoNotEnableTcpWhenPolicyDisablesTcp() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
        )

        assertEquals(false, options.tcpMode)
        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun wirelessAdbStartOptionsCanDisableManagedWirelessDebugging() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
            managedWirelessAdbEnabled = false,
        )

        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun wirelessAdbStartOptionsDisableManagedWirelessDebuggingWhenPermissionIsUndeclared() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
            managedWirelessAdbEnabled = true,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNDECLARED,
        )

        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun managedWirelessAdbStatusIsHiddenWhenPermissionIsUndeclared() {
        assertFalse(
            PrivilegeUiManagedWirelessAdbStatus.UNDECLARED.isVisibleManagedWirelessAdbStatus(),
        )
    }
}
