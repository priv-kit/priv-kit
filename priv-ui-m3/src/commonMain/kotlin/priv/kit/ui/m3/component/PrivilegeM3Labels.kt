package priv.kit.ui.m3.component

import priv.kit.ui.m3.resources.*
import priv.kit.ui.component.PrivilegeUiStartAction
import org.jetbrains.compose.resources.StringResource

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiStartActionLabel(
    action: PrivilegeUiStartAction,
    startLabel: StringResource,
): StringResource =
    when (action) {
        PrivilegeUiStartAction.CANCEL -> Res.string.priv_ui_start_cancel_action
        PrivilegeUiStartAction.CANCELLING -> Res.string.priv_ui_start_cancelling_action
        PrivilegeUiStartAction.START,
        PrivilegeUiStartAction.NONE,
        -> startLabel
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiPairingInputHint(notificationPairingRunning: Boolean): StringResource =
    if (notificationPairingRunning) {
        Res.string.priv_ui_pairing_input_hint
    } else {
        Res.string.priv_ui_pairing_split_screen_hint
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiWirelessAdbStartActionLabel(
    action: PrivilegeUiStartAction,
): StringResource = privilegeUiStartActionLabel(action, Res.string.priv_ui_adb_wireless_start_action)

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun staticTcpCommandHelpVisible(
    wirelessAdbSupported: Boolean,
): Boolean =
    !wirelessAdbSupported

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun staticTcpActionLabel(
    action: PrivilegeUiStartAction,
): StringResource = privilegeUiStartActionLabel(action, Res.string.priv_ui_adb_static_start_action)
