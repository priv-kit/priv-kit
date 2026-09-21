package priv.kit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.resources.*

@Composable
internal fun PrivilegeUiScreenScope.AdbPanel() {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = state.localNetworkPermissionMissing,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column {
                LocalNetworkPermissionPanel()
                Spacer(Modifier.height(PrivilegeUiSpacing.large))
            }
        }
        AnimatedVisibility(visible = state.batteryOptimizationPromptVisible) {
            Column {
                BatteryOptimizationPromptPanel()
                Spacer(Modifier.height(PrivilegeUiSpacing.large))
            }
        }
        Panel {
            val wirelessAdbVisible = state.wirelessAdbSupported
            val staticTcpVisible = state.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
            AdbFingerprintRow(
                fingerprint = state.adbKeyFingerprint,
            )
            if (!wirelessAdbVisible && !staticTcpVisible) {
                StatusText(stringResource(Res.string.priv_ui_adb_unavailable))
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (wirelessAdbVisible) {
                    WirelessAdbSection()
                }
                if (wirelessAdbVisible && staticTcpVisible) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (staticTcpVisible) {
                    StaticTcpAdbSection()
                }
            }
        }
    }
    state.staticTcpSwitchConfirmation?.let { action ->
        StaticTcpSwitchConfirmationDialog(action)
    }
    if (state.tcpAuthorizationFailureDialogVisible) {
        TcpAuthorizationFailureDialog()
    }
}
