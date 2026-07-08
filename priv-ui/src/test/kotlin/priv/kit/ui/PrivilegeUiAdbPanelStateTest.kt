package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbPanelStateTest {
    @Test
    fun wirelessAdbPanelStatusCollapsesProbeFacts() {
        listOf(
            WirelessPanelStatusCase(
                wifiConnected = false,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbPanelStatus.WIFI_REQUIRED,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
                expected = PrivilegeUiWirelessAdbPanelStatus.UNPAIRED,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbPanelStatus.PAIRABLE,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbPanelStatus.PAIRED,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbPanelStatus.OFF,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
                wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
                expected = PrivilegeUiWirelessAdbPanelStatus.OFF,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                wirelessAdbPanelStatus(
                    wifiConnected = case.wifiConnected,
                    wirelessDebuggingStatus = case.wirelessDebuggingStatus,
                    wirelessPairingServiceStatus = case.wirelessPairingServiceStatus,
                    wirelessPairingCheckStatus = case.wirelessPairingCheckStatus,
                ),
            )
        }
    }

    @Test
    fun wirelessAdbStartActionFollowsRuntimeAndPrerequisites() {
        listOf(
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                wifiConnected = true,
                startPrerequisiteAvailable = true,
                startAvailable = true,
                busy = true,
                expectedAction = PrivilegeUiWirelessAdbStartAction.INTERRUPT,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_start_interrupt_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                wifiConnected = true,
                startPrerequisiteAvailable = true,
                startAvailable = true,
                busy = false,
                expectedAction = PrivilegeUiWirelessAdbStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wifiConnected = true,
                startPrerequisiteAvailable = true,
                startAvailable = true,
                busy = true,
                expectedAction = PrivilegeUiWirelessAdbStartAction.START,
                expectedEnabled = false,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wifiConnected = false,
                startPrerequisiteAvailable = true,
                startAvailable = false,
                busy = false,
                expectedAction = PrivilegeUiWirelessAdbStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wifiConnected = false,
                startPrerequisiteAvailable = false,
                startAvailable = false,
                busy = false,
                expectedAction = PrivilegeUiWirelessAdbStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wifiConnected = true,
                startPrerequisiteAvailable = false,
                startAvailable = false,
                busy = false,
                expectedAction = PrivilegeUiWirelessAdbStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
        ).forEach { case ->
            val action = privilegeUiWirelessAdbStartAction(
                runtimeStatus = case.runtimeStatus,
                wifiConnected = case.wifiConnected,
                startPrerequisiteAvailable = case.startPrerequisiteAvailable,
                startAvailable = case.startAvailable,
            )

            assertEquals(case.expectedAction, action)
            assertEquals(
                case.expectedEnabled,
                privilegeUiWirelessAdbStartActionEnabled(
                    action = action,
                    busy = case.busy,
                    runtimeStatus = case.runtimeStatus,
                ),
            )
            assertEquals(case.expectedLabel, privilegeUiWirelessAdbStartActionLabel(action))
        }
    }

    @Test
    fun staticTcpActionsFollowPortAndAuthorizationState() {
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE,
            staticTcpPanelStatus(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE,
            staticTcpPanelStatus(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.CHECKING,
            staticTcpPanelStatus(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.CHECKING,
            staticTcpPanelStatus(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED,
            staticTcpPanelStatus(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.AUTHORIZED,
            staticTcpPanelStatus(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            ),
        )
        listOf(
            StaticTcpActionCase(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                wirelessAdbSupported = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                wirelessAdbSupported = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = false,
                expectedVisible = false,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                wirelessAdbSupported = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                wirelessAdbSupported = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = false,
                expectedVisible = false,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
                wirelessAdbSupported = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
                wirelessAdbSupported = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                wirelessAdbSupported = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_start_interrupt_action,
            ),
        ).forEach { case ->
            assertEquals(
                case.expectedEnabled,
                staticTcpActionEnabled(
                    tcpModeEnabled = case.tcpModeEnabled,
                    busy = false,
                    runtimeStatus = case.runtimeStatus,
                    status = case.status,
                    wirelessAdbSupported = case.wirelessAdbSupported,
                ),
            )
            assertEquals(
                case.expectedVisible,
                staticTcpActionVisible(
                    tcpModeEnabled = case.tcpModeEnabled,
                    runtimeStatus = case.runtimeStatus,
                    status = case.status,
                    wirelessAdbSupported = case.wirelessAdbSupported,
                ),
            )
            assertEquals(
                case.expectedCommandHelpVisible,
                staticTcpCommandHelpVisible(
                    tcpModeEnabled = case.tcpModeEnabled,
                    status = case.status,
                    wirelessAdbSupported = case.wirelessAdbSupported,
                ),
            )
            assertEquals(
                case.expectedLabel,
                staticTcpActionLabel(
                    runtimeStatus = case.runtimeStatus,
                ),
            )
        }
    }

    private data class WirelessActionCase(
        val runtimeStatus: PrivilegeUiRuntimeStatus,
        val wifiConnected: Boolean,
        val startPrerequisiteAvailable: Boolean,
        val startAvailable: Boolean,
        val busy: Boolean,
        val expectedAction: PrivilegeUiWirelessAdbStartAction,
        val expectedEnabled: Boolean,
        val expectedLabel: Int,
    )

    private data class StaticTcpActionCase(
        val tcpModeEnabled: Boolean,
        val status: PrivilegeUiAdbTcpAuthorizationStatus,
        val wirelessAdbSupported: Boolean,
        val runtimeStatus: PrivilegeUiRuntimeStatus,
        val expectedEnabled: Boolean,
        val expectedVisible: Boolean,
        val expectedCommandHelpVisible: Boolean,
        val expectedLabel: Int,
    )

    private data class WirelessPanelStatusCase(
        val wifiConnected: Boolean = true,
        val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.OFF,
        val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.OFF,
        val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
        val expected: PrivilegeUiWirelessAdbPanelStatus,
    )
}
