package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                expected = PrivilegeUiWirelessAdbPanelStatus.OFF,
            ),
            WirelessPanelStatusCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbPanelStatus.PAIRED,
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
    fun wirelessAdbStartActionFollowsSharedStartPhase() {
        listOf(
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
                ownsRuntimeStart = true,
                busy = true,
                expectedAction = PrivilegeUiStartAction.CANCEL,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_start_cancel_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
                ownsRuntimeStart = true,
                busy = true,
                expectedAction = PrivilegeUiStartAction.CANCELLING,
                expectedEnabled = false,
                expectedLabel = R.string.priv_ui_start_cancelling_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
                ownsRuntimeStart = false,
                busy = true,
                expectedAction = PrivilegeUiStartAction.NONE,
                expectedEnabled = false,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
                ownsRuntimeStart = false,
                busy = true,
                expectedAction = PrivilegeUiStartAction.NONE,
                expectedEnabled = false,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                ownsRuntimeStart = false,
                busy = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                ownsRuntimeStart = false,
                busy = true,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = false,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
            WirelessActionCase(
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                ownsRuntimeStart = false,
                busy = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = true,
                expectedLabel = R.string.priv_ui_adb_wireless_start_action,
            ),
        ).forEach { case ->
            val action = privilegeUiWirelessAdbStartAction(
                runtimeStartPhase = case.runtimeStartPhase,
                ownsRuntimeStart = case.ownsRuntimeStart,
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
            PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED,
            staticTcpPanelStatus(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED,
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
                wirelessAdbSupported = true,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                ownsRuntimeStart = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = false,
                wirelessAdbSupported = false,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                ownsRuntimeStart = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = false,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                wirelessAdbSupported = false,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                ownsRuntimeStart = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = true,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                wirelessAdbSupported = true,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                ownsRuntimeStart = false,
                expectedAction = PrivilegeUiStartAction.START,
                expectedEnabled = true,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = false,
                wirelessAdbSupported = false,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
                ownsRuntimeStart = true,
                expectedAction = PrivilegeUiStartAction.CANCEL,
                expectedEnabled = true,
                expectedCommandHelpVisible = true,
                expectedLabel = R.string.priv_ui_start_cancel_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                wirelessAdbSupported = true,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
                ownsRuntimeStart = true,
                expectedAction = PrivilegeUiStartAction.CANCELLING,
                expectedEnabled = false,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_start_cancelling_action,
            ),
            StaticTcpActionCase(
                tcpModeEnabled = true,
                wirelessAdbSupported = true,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
                ownsRuntimeStart = false,
                expectedAction = PrivilegeUiStartAction.NONE,
                expectedEnabled = false,
                expectedCommandHelpVisible = false,
                expectedLabel = R.string.priv_ui_adb_static_start_action,
            ),
        ).forEach { case ->
            val action = staticTcpStartAction(
                runtimeStartPhase = case.runtimeStartPhase,
                ownsRuntimeStart = case.ownsRuntimeStart,
            )
            assertEquals(case.expectedAction, action)
            assertEquals(
                case.expectedEnabled,
                staticTcpActionEnabled(
                    action = action,
                    tcpModeEnabled = case.tcpModeEnabled,
                    busy = case.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE,
                    runtimeStatus = case.runtimeStatus,
                    wirelessAdbSupported = case.wirelessAdbSupported,
                ),
            )
            assertEquals(
                case.expectedCommandHelpVisible,
                staticTcpCommandHelpVisible(
                    wirelessAdbSupported = case.wirelessAdbSupported,
                ),
            )
            assertEquals(
                case.expectedLabel,
                staticTcpActionLabel(action),
            )
        }
    }

    @Test
    fun configuredButInactiveTcpPortCanRecoverWithoutWireless() {
        assertEquals(
            PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED,
            staticTcpPanelStatus(
                tcpModeConfigured = true,
                tcpModeActive = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
        assertTrue(
            staticTcpActionEnabled(
                action = PrivilegeUiStartAction.START,
                tcpModeEnabled = false,
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                wirelessAdbSupported = false,
                tcpModeConfigured = true,
            ),
        )
    }

    @Test
    fun configuredTcpPortDoesNotReportTcpModeAsActive() {
        val store = PrivilegeUiViewModelStore()

        store.updateConfiguredTcpModePort(5555)
        store.updateTcpModePort(null)

        assertEquals(5555, store.state.value.configuredTcpModePort)
        assertEquals(null, store.state.value.tcpModePort)
        assertEquals(false, store.tcpModeEnabled.value)
    }

    private data class WirelessActionCase(
        val runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
        val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
        val ownsRuntimeStart: Boolean,
        val busy: Boolean,
        val expectedAction: PrivilegeUiStartAction,
        val expectedEnabled: Boolean,
        val expectedLabel: Int,
    )

    private data class StaticTcpActionCase(
        val tcpModeEnabled: Boolean,
        val wirelessAdbSupported: Boolean,
        val runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
        val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
        val ownsRuntimeStart: Boolean,
        val expectedAction: PrivilegeUiStartAction,
        val expectedEnabled: Boolean,
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
