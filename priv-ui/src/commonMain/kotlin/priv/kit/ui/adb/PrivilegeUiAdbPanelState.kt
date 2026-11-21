package priv.kit.ui.adb

import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiScreenState
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.component.PrivilegeUiStartAction
import priv.kit.ui.component.privilegeUiStartActionEnabled

@priv.kit.ui.ExperimentalPrivilegeUiApi
public enum class PrivilegeUiWirelessAdbPanelStatus {
    WIFI_REQUIRED,
    OFF,
    UNPAIRED,
    PAIRABLE,
    PAIRED,
}

@priv.kit.ui.ExperimentalPrivilegeUiApi
public enum class PrivilegeUiStaticTcpPanelStatus {
    PORT_NOT_CONFIGURED,
    ADB_SERVICE_STOPPED,
    UNAUTHORIZED,
    AUTHORIZED,
}

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun wirelessAdbPanelStatus(
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


@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun staticTcpPanelStatus(
    tcpModeConfigured: Boolean,
    tcpModeActive: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiStaticTcpPanelStatus =
    when {
        !tcpModeConfigured -> PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED
        !tcpModeActive || status == PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE ->
            PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED
        status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> PrivilegeUiStaticTcpPanelStatus.AUTHORIZED
        else -> PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun staticTcpActionEnabled(
    action: PrivilegeUiStartAction,
    busy: Boolean,
    wirelessAdbSupported: Boolean,
    tcpModeConfigured: Boolean,
): Boolean =
    privilegeUiStartActionEnabled(
        action = action,
        startEnabled = !busy &&
            (tcpModeConfigured || wirelessAdbSupported),
    )

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun PrivilegeUiScreenState.staticTcpStartActionEnabled(
    action: PrivilegeUiStartAction,
    wirelessAdbSupported: Boolean,
    interactionEnabled: Boolean,
): Boolean =
    staticTcpActionEnabled(
        action = action,
        busy = busy,
        wirelessAdbSupported = wirelessAdbSupported,
        tcpModeConfigured = staticTcp.configuredPort != null,
    ) &&
        interactionEnabled
