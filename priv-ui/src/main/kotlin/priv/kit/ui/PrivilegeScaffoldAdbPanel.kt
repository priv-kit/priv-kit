package priv.kit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun AdbPanel(
    state: PrivilegeUiState,
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    onPairingCodeChanged: (String) -> Unit,
    onPairByCode: () -> Unit,
    onNotificationPairingClick: () -> Unit,
    onEnableTcpMode: () -> Unit,
    onStartAdb: () -> Unit,
) {
    Panel {
        val paired = state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val tcpAvailable = tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED && tcpModeEnabled
        val tcpAuthorized = tcpAvailable &&
            state.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        val tcpAuthorizationActionAvailable = tcpAvailable &&
            state.tcpAuthorizationStatus in setOf(
                PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
            )
        val tcpAuthorizationWaiting = tcpAvailable &&
            state.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
        val adbEndpointAvailable = state.wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON || tcpAuthorized
        val canStart = paired || adbEndpointAvailable || tcpAuthorizationActionAvailable || tcpAuthorizationWaiting
        Text(
            text = stringResource(R.string.priv_ui_adb_authorization_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AdbRealtimeStatusPanel(
            state = state,
            tcpModeEnabled = tcpModeEnabled,
            tcpPolicy = tcpPolicy,
        )
        AnimatedVisibility(visible = !paired && !tcpAvailable) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.pairingCode,
                    onValueChange = onPairingCodeChanged,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text(stringResource(R.string.priv_ui_pairing_code)) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    onClick = onPairByCode,
                ) {
                    Text(stringResource(R.string.priv_ui_wireless_pair_action))
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy || state.notificationPairingRunning,
                    onClick = onNotificationPairingClick,
                ) {
                    Text(
                        if (state.notificationPairingRunning) {
                            stringResource(R.string.priv_ui_notification_pairing_waiting)
                        } else {
                            stringResource(R.string.priv_ui_pair_via_notification)
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = paired &&
                !tcpAvailable &&
                tcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED,
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                onClick = onEnableTcpMode,
            ) {
                Text(stringResource(R.string.priv_ui_adb_prepare_stable_action))
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && canStart,
            onClick = onStartAdb,
        ) {
            Text(
                stringResource(
                    if (tcpAuthorizationActionAvailable) {
                        R.string.priv_ui_adb_key_authorization_action
                    } else if (tcpAuthorizationWaiting) {
                        R.string.priv_ui_tcp_authorization_waiting_action
                    } else {
                        R.string.priv_ui_adb_authorization_action
                    },
                ),
            )
        }
    }
}

@Composable
private fun AdbRealtimeStatusPanel(
    state: PrivilegeUiState,
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.priv_ui_wireless_realtime_status_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            AdbFingerprintRow(fingerprint = state.adbKeyFingerprint)
            RealtimeStatusRow(
                label = stringResource(R.string.priv_ui_wireless_status_debugging),
                status = state.wirelessDebuggingStatus,
            )
            AnimatedVisibility(
                visible = state.wirelessPairingCheckStatus != PrivilegeUiWirelessAdbStatus.ON &&
                    !tcpModeEnabled,
            ) {
                RealtimeStatusRow(
                    label = stringResource(R.string.priv_ui_wireless_status_pairing_service),
                    status = state.wirelessPairingServiceStatus,
                )
            }
            RealtimeStatusRow(
                label = stringResource(R.string.priv_ui_wireless_status_pairing_check),
                status = state.wirelessPairingCheckStatus,
                positiveLabel = stringResource(R.string.priv_ui_wireless_status_yes),
                negativeLabel = stringResource(R.string.priv_ui_wireless_status_no),
            )
            if (tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                StableAdbStatusRow(enabled = tcpModeEnabled)
                TcpAuthorizationStatusRow(status = state.tcpAuthorizationStatus)
            }
        }
    }
}

@Composable
private fun AdbFingerprintRow(fingerprint: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.priv_ui_adb_key_fingerprint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = fingerprint ?: stringResource(R.string.priv_ui_adb_key_fingerprint_unavailable),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StableAdbStatusRow(enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.priv_ui_adb_stable_status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (enabled) {
                    R.string.priv_ui_adb_stable_ready
                } else {
                    R.string.priv_ui_adb_stable_unavailable
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TcpAuthorizationStatusRow(status: PrivilegeUiAdbTcpAuthorizationStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.priv_ui_adb_key_authorization_status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = status.displayText(),
            style = MaterialTheme.typography.labelLarge,
            color = status.displayColor(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RealtimeStatusRow(
    label: String,
    status: PrivilegeUiWirelessAdbStatus,
    positiveLabel: String = stringResource(R.string.priv_ui_wireless_status_on),
    negativeLabel: String = stringResource(R.string.priv_ui_wireless_status_off),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = status.displayText(
                positiveLabel = positiveLabel,
                negativeLabel = negativeLabel,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = status.displayColor(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PrivilegeUiWirelessAdbStatus.displayText(
    positiveLabel: String,
    negativeLabel: String,
): String =
    when (this) {
        PrivilegeUiWirelessAdbStatus.UNKNOWN -> stringResource(R.string.priv_ui_wireless_status_unknown)
        PrivilegeUiWirelessAdbStatus.CHECKING -> stringResource(R.string.priv_ui_wireless_status_checking)
        PrivilegeUiWirelessAdbStatus.ON -> positiveLabel
        PrivilegeUiWirelessAdbStatus.OFF -> negativeLabel
    }

@Composable
private fun PrivilegeUiWirelessAdbStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiWirelessAdbStatus.ON -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiWirelessAdbStatus.CHECKING -> MaterialTheme.colorScheme.primary
        PrivilegeUiWirelessAdbStatus.OFF,
        PrivilegeUiWirelessAdbStatus.UNKNOWN,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun PrivilegeUiAdbTcpAuthorizationStatus.displayText(): String =
    when (this) {
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN -> stringResource(R.string.priv_ui_wireless_status_unknown)
        PrivilegeUiAdbTcpAuthorizationStatus.CHECKING -> stringResource(R.string.priv_ui_wireless_status_checking)
        PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING -> stringResource(R.string.priv_ui_tcp_authorization_waiting)
        PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> stringResource(R.string.priv_ui_tcp_authorization_authorized)
        PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED -> stringResource(R.string.priv_ui_tcp_authorization_unauthorized)
        PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> stringResource(R.string.priv_ui_adb_stable_unavailable)
        PrivilegeUiAdbTcpAuthorizationStatus.FAILED -> stringResource(R.string.priv_ui_tcp_authorization_failed)
    }

@Composable
private fun PrivilegeUiAdbTcpAuthorizationStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
        PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
        -> MaterialTheme.colorScheme.primary
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
        PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
        PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
        PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
