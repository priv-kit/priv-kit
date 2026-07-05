package priv.kit.ui

internal enum class PrivilegeUiWirelessAdbStartAction {
    START,
    WIFI_REQUIRED,
    STOP,
    NONE,
}

internal fun privilegeUiWirelessAdbStartAction(
    runtimeStatus: PrivilegeUiRuntimeStatus,
    wifiConnected: Boolean,
    startPrerequisiteAvailable: Boolean,
    startAvailable: Boolean,
): PrivilegeUiWirelessAdbStartAction =
    when (runtimeStatus) {
        PrivilegeUiRuntimeStatus.STARTING -> PrivilegeUiWirelessAdbStartAction.STOP
        PrivilegeUiRuntimeStatus.CONNECTED,
        PrivilegeUiRuntimeStatus.DISCONNECTED,
        PrivilegeUiRuntimeStatus.FAILED,
        -> when {
            startAvailable -> PrivilegeUiWirelessAdbStartAction.START
            !wifiConnected -> PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED
            else -> PrivilegeUiWirelessAdbStartAction.START
        }
    }

internal fun privilegeUiWirelessAdbStartActionEnabled(
    action: PrivilegeUiWirelessAdbStartAction,
    busy: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
): Boolean =
    when (action) {
        PrivilegeUiWirelessAdbStartAction.START,
        PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED,
        -> !busy
        PrivilegeUiWirelessAdbStartAction.STOP -> runtimeStatus == PrivilegeUiRuntimeStatus.STARTING || !busy
        PrivilegeUiWirelessAdbStartAction.NONE -> false
    }

internal fun privilegeUiWirelessAdbStartActionLabel(
    action: PrivilegeUiWirelessAdbStartAction,
): Int =
    when (action) {
        PrivilegeUiWirelessAdbStartAction.STOP -> R.string.priv_ui_adb_wireless_stop_action
        PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED -> R.string.priv_ui_adb_wireless_wifi_required_action
        PrivilegeUiWirelessAdbStartAction.START,
        PrivilegeUiWirelessAdbStartAction.NONE,
        -> R.string.priv_ui_adb_wireless_start_action
    }

internal fun staticTcpAuthorizationDisplayStatus(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiAdbTcpAuthorizationStatus =
    if (tcpModeEnabled) status else PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN

internal fun staticTcpActionEnabled(
    tcpModeEnabled: Boolean,
    busy: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): Boolean =
    tcpModeEnabled && !busy

internal fun staticTcpActionVisible(tcpModeEnabled: Boolean): Boolean =
    tcpModeEnabled

internal fun staticTcpCommandHelpVisible(tcpModeEnabled: Boolean): Boolean =
    !tcpModeEnabled

internal fun staticTcpActionLabel(
    tcpModeEnabled: Boolean,
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): Int =
    when {
        !tcpModeEnabled -> R.string.priv_ui_adb_static_use_other_method_action
        status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> R.string.priv_ui_adb_static_start_action
        status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING -> R.string.priv_ui_tcp_authorization_cancel_action
        status == PrivilegeUiAdbTcpAuthorizationStatus.CHECKING -> R.string.priv_ui_wireless_status_checking
        status == PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED ||
            status == PrivilegeUiAdbTcpAuthorizationStatus.FAILED ->
            R.string.priv_ui_adb_static_authorize_action
        else -> R.string.priv_ui_adb_static_check_action
    }
