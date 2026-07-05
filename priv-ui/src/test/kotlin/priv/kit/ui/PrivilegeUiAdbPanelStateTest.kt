package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbPanelStateTest {
    @Test
    fun wirelessAdbStartActionFollowsRuntimeAndPrerequisites() {
        listOf(
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                wifiConnected = true,
                startPrerequisiteAvailable = true,
                startAvailable = true,
                busy = true,
                expectedAction = PrivilegeUiWirelessAdbStartAction.STOP,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_stop_action,
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
                expectedAction = PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_wifi_required_action,
            ),
            WirelessActionCase(
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wifiConnected = false,
                startPrerequisiteAvailable = false,
                startAvailable = false,
                busy = false,
                expectedAction = PrivilegeUiWirelessAdbStartAction.NONE,
                expectedEnabled = false,
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
            PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            staticTcpAuthorizationDisplayStatus(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
        listOf(
            StaticTcpActionCase(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                expectedEnabled = false,
                expectedVisible = false,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_adb_static_use_other_method_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
                expectedEnabled = true,
                expectedVisible = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
        ).forEach { case ->
            assertEquals(
                case.expectedEnabled,
                staticTcpActionEnabled(
                    tcpModeEnabled = case.tcpModeEnabled,
                    busy = false,
                    status = case.status,
                ),
            )
            assertEquals(case.expectedVisible, staticTcpActionVisible(case.tcpModeEnabled))
            assertEquals(case.expectedCommandHelpVisible, staticTcpCommandHelpVisible(case.tcpModeEnabled))
            assertEquals(
                case.expectedLabel,
                staticTcpActionLabel(
                    tcpModeEnabled = case.tcpModeEnabled,
                    status = case.status,
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
        val expectedEnabled: Boolean,
        val expectedVisible: Boolean,
        val expectedCommandHelpVisible: Boolean,
        val expectedLabel: Int,
    )
}
