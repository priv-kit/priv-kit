package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.component.privilegeUiStartAction
import priv.kit.ui.component.privilegeUiStartActionEnabled
import priv.kit.ui.component.privilegeUiStartActionLabel
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

internal enum class PrivilegeUiWirelessAdbPanelStatus {
    WIFI_REQUIRED,
    OFF,
    UNPAIRED,
    PAIRABLE,
    PAIRED,
}

internal enum class PrivilegeUiStaticTcpPanelStatus {
    PORT_NOT_CONFIGURED,
    ADB_SERVICE_STOPPED,
    CHECKING,
    UNAUTHORIZED,
    AUTHORIZED,
}

internal fun wirelessAdbPanelStatus(
    wifiConnected: Boolean,
    wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
    wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus,
    wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus,
): PrivilegeUiWirelessAdbPanelStatus {
    return when {
        !wifiConnected -> PrivilegeUiWirelessAdbPanelStatus.WIFI_REQUIRED
        wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.OFF ->
            PrivilegeUiWirelessAdbPanelStatus.OFF
        wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON ->
            PrivilegeUiWirelessAdbPanelStatus.PAIRED
        wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.OFF ->
            PrivilegeUiWirelessAdbPanelStatus.UNPAIRED
        wirelessPairingServiceStatus == PrivilegeUiWirelessAdbStatus.ON ->
            PrivilegeUiWirelessAdbPanelStatus.PAIRABLE
        wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON ->
            PrivilegeUiWirelessAdbPanelStatus.PAIRED
        else -> PrivilegeUiWirelessAdbPanelStatus.OFF
    }
}

internal fun privilegeUiWirelessAdbStartAction(
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
    ownsRuntimeStart: Boolean,
): PrivilegeUiStartAction =
    privilegeUiStartAction(runtimeStartPhase, ownsRuntimeStart)

@Suppress("UNUSED_PARAMETER")
internal fun privilegeUiWirelessAdbStartActionEnabled(
    action: PrivilegeUiStartAction,
    busy: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
): Boolean =
    privilegeUiStartActionEnabled(
        action,
        startEnabled = !busy,
    )

internal fun privilegeUiWirelessAdbStartActionLabel(
    action: PrivilegeUiStartAction,
): Int = privilegeUiStartActionLabel(action, R.string.priv_ui_adb_wireless_start_action)

internal fun staticTcpPanelStatus(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiStaticTcpPanelStatus =
    staticTcpPanelStatus(
        tcpModeConfigured = tcpModeEnabled,
        tcpModeActive = tcpModeEnabled,
        status = status,
    )

internal fun staticTcpPanelStatus(
    tcpModeConfigured: Boolean,
    tcpModeActive: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiStaticTcpPanelStatus =
    when {
        !tcpModeConfigured -> PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED
        !tcpModeActive || status == PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE ->
            PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED
        status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> PrivilegeUiStaticTcpPanelStatus.AUTHORIZED
        status == PrivilegeUiAdbTcpAuthorizationStatus.CHECKING ||
            status == PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN -> PrivilegeUiStaticTcpPanelStatus.CHECKING
        else -> PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED
    }

internal fun staticTcpStartAction(
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
    ownsRuntimeStart: Boolean,
): PrivilegeUiStartAction =
    privilegeUiStartAction(runtimeStartPhase, ownsRuntimeStart)

@Suppress("UNUSED_PARAMETER")
internal fun staticTcpActionEnabled(
    action: PrivilegeUiStartAction,
    tcpModeEnabled: Boolean,
    busy: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
    wirelessAdbSupported: Boolean,
    tcpModeConfigured: Boolean = tcpModeEnabled,
): Boolean =
    privilegeUiStartActionEnabled(
        action = action,
        startEnabled = !busy &&
            (tcpModeConfigured || wirelessAdbSupported),
    )

internal fun staticTcpCommandHelpVisible(
    wirelessAdbSupported: Boolean,
): Boolean =
    !wirelessAdbSupported

internal fun staticTcpActionLabel(
    action: PrivilegeUiStartAction,
): Int = privilegeUiStartActionLabel(action, R.string.priv_ui_adb_static_start_action)
