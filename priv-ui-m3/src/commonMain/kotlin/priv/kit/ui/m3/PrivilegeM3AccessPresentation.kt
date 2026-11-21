package priv.kit.ui.m3

import priv.kit.ui.m3.resources.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import priv.kit.ui.*

@Composable
internal fun PrivilegeM3AccessExplanationDialog(state: PrivilegeM3State, actions: PrivilegeM3Actions) {
    val explanation = state.accessExplanation ?: return
    val title = when (explanation) {
        PrivilegeM3AccessExplanation.NOTIFICATIONS -> Res.string.priv_ui_system_prompt_notification_title
        PrivilegeM3AccessExplanation.LOCAL_NETWORK -> Res.string.priv_ui_system_prompt_local_network_title
        PrivilegeM3AccessExplanation.BATTERY_OPTIMIZATION -> Res.string.priv_ui_battery_optimization_title
    }
    AlertDialog(
        onDismissRequest = { actions.answerAccessExplanation(false) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(
            if (explanation == PrivilegeM3AccessExplanation.BATTERY_OPTIMIZATION)
                Res.string.priv_ui_battery_optimization_message else Res.string.priv_ui_permission_settings_explanation)) },
        confirmButton = {
            TextButton(onClick = { actions.answerAccessExplanation(true) }) {
                Text(stringResource(Res.string.priv_ui_local_network_permission_settings_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { actions.answerAccessExplanation(false) }) {
                Text(stringResource(Res.string.priv_ui_pairing_cancel_action))
            }
        },
    )
}

@Composable
internal fun privilegeM3AuthorizationPrompt(authorization: PrivilegeUiAuthorization?): PrivilegeUiPromptState? {
    authorization ?: return null
    val (title, message) = when (authorization.kind) {
        PrivilegeUiAuthorizationKind.ROOT -> Res.string.priv_ui_system_prompt_root_title to Res.string.priv_ui_system_prompt_root_message
        PrivilegeUiAuthorizationKind.TCP -> Res.string.priv_ui_system_prompt_tcp_authorization_title to Res.string.priv_ui_system_prompt_tcp_authorization_message
        PrivilegeUiAuthorizationKind.WIRELESS_DEBUGGING -> Res.string.priv_ui_system_prompt_wireless_debugging_title to Res.string.priv_ui_system_prompt_wireless_debugging_message
        PrivilegeUiAuthorizationKind.EXTERNAL -> Res.string.priv_ui_system_prompt_external_title to Res.string.priv_ui_system_prompt_external_message
    }
    return if (authorization.kind == PrivilegeUiAuthorizationKind.EXTERNAL) {
        PrivilegeUiPromptState(stringResource(title, authorization.providerLabel.orEmpty()), stringResource(message, authorization.providerLabel.orEmpty()))
    } else PrivilegeUiPromptState(stringResource(title), stringResource(message))
}
