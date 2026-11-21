package priv.kit.ui.m3.component

import priv.kit.ui.m3.resources.*
import priv.kit.ui.*
import priv.kit.ui.component.*

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import priv.kit.ui.m3.PrivilegeUiScreenScope

@Composable
@ExperimentalPrivilegeUiApi
public fun PrivilegeUiScreenScope.LocalNetworkPermissionPanel() {
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
                Res.string.priv_ui_local_network_permission_grant_action,
            ))
        }
    }
}

@Composable
@ExperimentalPrivilegeUiApi
public fun PrivilegeUiScreenScope.BatteryOptimizationPromptPanel() {
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
                if (actions.canInteract()) actions.requestBatteryOptimization()
            },
        ) {
            Text(stringResource(Res.string.priv_ui_battery_optimization_settings_action))
        }
    }
}
