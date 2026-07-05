package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivilegeUiAdbActionsTest {
    @Test
    fun pairingCheckStatusUsesWirelessAndRefreshResult() {
        listOf(
            PairingCheckCase(
                wirelessDebuggingOn = false,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbStatus.ON,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = false,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.OFF,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.ON,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                privilegeUiPairingCheckStatus(
                    wirelessDebuggingOn = case.wirelessDebuggingOn,
                    pairingCheckPaired = case.pairingCheckPaired,
                    currentStatus = case.currentStatus,
                ),
            )
        }
    }

    @Test
    fun refreshingPairingCheckStatusReflectsWirelessState() {
        listOf(
            RefreshingPairingCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
            RefreshingPairingCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbStatus.CHECKING,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                privilegeUiRefreshingPairingCheckStatus(
                    wirelessDebuggingStatus = case.wirelessDebuggingStatus,
                    currentStatus = case.currentStatus,
                ),
            )
        }
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

    private data class PairingCheckCase(
        val wirelessDebuggingOn: Boolean,
        val pairingCheckPaired: Boolean?,
        val currentStatus: PrivilegeUiWirelessAdbStatus,
        val expected: PrivilegeUiWirelessAdbStatus,
    )

    private data class RefreshingPairingCase(
        val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
        val currentStatus: PrivilegeUiWirelessAdbStatus,
        val expected: PrivilegeUiWirelessAdbStatus,
    )
}
