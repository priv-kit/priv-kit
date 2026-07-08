package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

internal enum class PrivilegeUiWirelessAdbStartAction {
    START,
    INTERRUPT,
    NONE,
}

internal enum class PrivilegeUiWirelessAdbPanelStatus {
    WIFI_REQUIRED,
    OFF,
    UNPAIRED,
    PAIRABLE,
    PAIRED,
}

internal enum class PrivilegeUiStaticTcpPanelStatus {
    UNAVAILABLE,
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
    runtimeStatus: PrivilegeUiRuntimeStatus,
    wifiConnected: Boolean,
    startPrerequisiteAvailable: Boolean,
    startAvailable: Boolean,
): PrivilegeUiWirelessAdbStartAction =
    when (runtimeStatus) {
        PrivilegeUiRuntimeStatus.STARTING -> PrivilegeUiWirelessAdbStartAction.INTERRUPT
        PrivilegeUiRuntimeStatus.CONNECTED,
        PrivilegeUiRuntimeStatus.DISCONNECTED,
        PrivilegeUiRuntimeStatus.FAILED,
        -> when {
            startAvailable -> PrivilegeUiWirelessAdbStartAction.START
            else -> PrivilegeUiWirelessAdbStartAction.START
        }
    }

internal fun privilegeUiWirelessAdbStartActionEnabled(
    action: PrivilegeUiWirelessAdbStartAction,
    busy: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
): Boolean =
    when (action) {
        PrivilegeUiWirelessAdbStartAction.START -> !busy
        PrivilegeUiWirelessAdbStartAction.INTERRUPT -> runtimeStatus == PrivilegeUiRuntimeStatus.STARTING || !busy
        PrivilegeUiWirelessAdbStartAction.NONE -> false
    }

internal fun privilegeUiWirelessAdbStartActionLabel(
    action: PrivilegeUiWirelessAdbStartAction,
): Int =
    when (action) {
        PrivilegeUiWirelessAdbStartAction.INTERRUPT -> R.string.priv_ui_start_interrupt_action
        PrivilegeUiWirelessAdbStartAction.START,
        PrivilegeUiWirelessAdbStartAction.NONE,
        -> R.string.priv_ui_adb_wireless_start_action
    }

internal fun staticTcpPanelStatus(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiStaticTcpPanelStatus =
    when {
        !tcpModeEnabled ||
            status == PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE
        status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> PrivilegeUiStaticTcpPanelStatus.AUTHORIZED
        status == PrivilegeUiAdbTcpAuthorizationStatus.CHECKING ||
            status == PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN -> PrivilegeUiStaticTcpPanelStatus.CHECKING
        else -> PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED
    }

internal fun staticTcpActionEnabled(
    tcpModeEnabled: Boolean,
    busy: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
    wirelessAdbSupported: Boolean,
): Boolean {
    if (runtimeStatus == PrivilegeUiRuntimeStatus.STARTING) return true
    val wirelessFallback = staticTcpWirelessFallbackAvailable(
        tcpModeEnabled = tcpModeEnabled,
        status = status,
        wirelessAdbSupported = wirelessAdbSupported,
    )
    return !busy && (
        wirelessFallback ||
            (tcpModeEnabled && status != PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE)
    )
}

internal fun staticTcpActionVisible(
    tcpModeEnabled: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
    wirelessAdbSupported: Boolean,
): Boolean {
    if (runtimeStatus == PrivilegeUiRuntimeStatus.STARTING) return true
    val wirelessFallback = staticTcpWirelessFallbackAvailable(
        tcpModeEnabled = tcpModeEnabled,
        status = status,
        wirelessAdbSupported = wirelessAdbSupported,
    )
    return wirelessFallback ||
        (tcpModeEnabled && status != PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE)
}

internal fun staticTcpCommandHelpVisible(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
    wirelessAdbSupported: Boolean,
): Boolean =
    !wirelessAdbSupported &&
        (!tcpModeEnabled || status == PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE)

internal fun staticTcpActionLabel(
    runtimeStatus: PrivilegeUiRuntimeStatus,
): Int =
    when (runtimeStatus) {
        PrivilegeUiRuntimeStatus.STARTING -> R.string.priv_ui_start_interrupt_action
        PrivilegeUiRuntimeStatus.CONNECTED,
        PrivilegeUiRuntimeStatus.DISCONNECTED,
        PrivilegeUiRuntimeStatus.FAILED,
        -> R.string.priv_ui_adb_static_start_action
    }

private fun staticTcpWirelessFallbackAvailable(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
    wirelessAdbSupported: Boolean,
): Boolean =
    wirelessAdbSupported &&
        (!tcpModeEnabled || status == PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE)
