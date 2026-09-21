package priv.kit.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.resources.*

@Composable
internal fun PrivilegeUiScreenScope.LocalNetworkPermissionPanel() {
    Panel {
        Text(
            text = stringResource(Res.string.priv_ui_local_network_permission_required),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.priv_ui_local_network_permission_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = interactionEnabled,
            onClick = actions.requestLocalNetworkPermission,
        ) {
            Text(stringResource(
                if (state.localNetworkPermissionSettingsRequired) {
                    Res.string.priv_ui_local_network_permission_settings_action
                } else {
                    Res.string.priv_ui_local_network_permission_grant_action
                },
            ))
        }
    }
}

@Composable
internal fun PrivilegeUiScreenScope.BatteryOptimizationPromptPanel() {
    val settingsUnavailable = stringResource(
        Res.string.priv_ui_battery_optimization_settings_unavailable,
    )
    Panel {
        Text(
            text = stringResource(Res.string.priv_ui_battery_optimization_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.priv_ui_battery_optimization_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = interactionEnabled,
            onClick = {
                if (
                    actions.canInteract() &&
                    !actions.requestBatteryOptimization()
                ) {
                    showFeedback(settingsUnavailable)
                }
            },
        ) {
            Text(stringResource(Res.string.priv_ui_battery_optimization_settings_action))
        }
    }
}
