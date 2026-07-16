package priv.kit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import priv.kit.ui.PrivilegeUiAdbPairingStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.R
import priv.kit.ui.adb.PrivilegeUiAdbStartupSection
import priv.kit.ui.adb.PrivilegeUiStaticTcpPanelStatus
import priv.kit.ui.adb.PrivilegeUiWirelessAdbPanelStatus
import priv.kit.ui.adb.privilegeUiAdbStartupSections
import priv.kit.ui.adb.privilegeUiWirelessAdbStartAction
import priv.kit.ui.adb.privilegeUiWirelessAdbStartActionEnabled
import priv.kit.ui.adb.privilegeUiWirelessAdbStartActionLabel
import priv.kit.ui.adb.staticTcpActionEnabled
import priv.kit.ui.adb.staticTcpActionLabel
import priv.kit.ui.adb.staticTcpStartAction
import priv.kit.ui.adb.staticTcpCommandHelpVisible
import priv.kit.ui.adb.staticTcpPanelStatus
import priv.kit.ui.adb.wirelessAdbPanelStatus
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingCode
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand

@Composable
internal fun PrivilegeUiScreenScope.AdbPanel() {
    Panel {
        val sections = privilegeUiAdbStartupSections(
            wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
            tcpPolicy = viewModel.config.adbTcpPolicy,
        )
        AdbFingerprintRow(fingerprint = state.adbKeyFingerprint)
        if (PrivilegeUiAdbStartupSection.WIRELESS in sections) {
            WirelessAdbSection()
        }
        if (PrivilegeUiAdbStartupSection.STATIC_TCP in sections) {
            StaticTcpAdbSection()
        }
        if (sections.isEmpty()) {
            StatusText(stringResource(R.string.priv_ui_adb_unavailable))
        }
    }
}

@Composable
private fun PrivilegeUiScreenScope.WirelessAdbSection() {
    ItemPanel {
        val wirelessStatus = wirelessAdbPanelStatus(
            wifiConnected = state.wifiConnected,
            wirelessDebuggingStatus = state.wirelessDebuggingStatus,
            wirelessPairingServiceStatus = state.wirelessPairingServiceStatus,
            wirelessPairingCheckStatus = state.wirelessPairingCheckStatus,
        )
        val runtimeStartInProgress = state.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE
        val wirelessOwnsRuntimeStart = runtimeStartInProgress &&
            state.runtimeStartSource == PrivilegeUiRuntimeStartSource.ADB_WIRELESS
        val wirelessAdbPaired = state.wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON &&
            state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val pairingActionEnabled = !runtimeStartInProgress &&
            !wirelessAdbPaired &&
            (!state.busy || state.notificationPairingRunning)
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_wireless),
            text = wirelessStatus.displayText(),
            color = wirelessStatus.displayColor(),
        )
        if (state.pairingDialogVisible) {
            WirelessAdbPairingDialog()
        }
        val startAction = privilegeUiWirelessAdbStartAction(
            runtimeStartPhase = state.runtimeStartPhase,
            ownsRuntimeStart = wirelessOwnsRuntimeStart,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = pairingActionEnabled,
                onClick = viewModel::startNotificationPairing,
            ) {
                Text(stringResource(R.string.priv_ui_wireless_pair_action))
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = privilegeUiWirelessAdbStartActionEnabled(
                    action = startAction,
                    busy = state.busy,
                ),
                onClick = {
                    when (startAction) {
                        PrivilegeUiStartAction.START ->
                            viewModel.startWirelessAdb()
                        PrivilegeUiStartAction.CANCEL -> viewModel.stopCurrentStart()
                        PrivilegeUiStartAction.CANCELLING,
                        PrivilegeUiStartAction.NONE,
                        -> Unit
                    }
                },
            ) {
                Text(
                    stringResource(
                        privilegeUiWirelessAdbStartActionLabel(startAction),
                    ),
                )
            }
        }
    }
}

@Composable
private fun PrivilegeUiScreenScope.WirelessAdbPairingDialog() {
    val defaultPairingMessage = stringResource(R.string.priv_ui_pairing_default_message)
    val pairing = state.pairingStatus == PrivilegeUiAdbPairingStatus.PAIRING
    val canSubmit = state.notificationPairingRunning &&
        !pairing &&
        state.pairingCode.isPrivilegeUiPairingCode()
    fun dismissOrStop() {
        if (pairing) {
            viewModel.stopNotificationPairing()
        } else {
            if (state.notificationPairingRunning) {
                viewModel.stopNotificationPairing()
            }
            viewModel.closePairingDialog()
        }
    }
    AlertDialog(
        onDismissRequest = ::dismissOrStop,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(stringResource(R.string.priv_ui_wireless_pair_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = state.pairingMessage.ifBlank { defaultPairingMessage },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.pairingCode,
                    onValueChange = viewModel::updatePairingCode,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) {
                                viewModel.submitNotificationPairingCode()
                            }
                        },
                    ),
                    label = { Text(stringResource(R.string.priv_ui_pairing_code)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = viewModel::submitNotificationPairingCode,
            ) {
                Text(stringResource(R.string.priv_ui_pairing_submit_action))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismissOrStop) {
                Text(
                    stringResource(
                        if (pairing) {
                            R.string.priv_ui_pairing_stop_action
                        } else {
                            R.string.priv_ui_pairing_cancel_action
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun PrivilegeUiScreenScope.StaticTcpAdbSection() {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.priv_ui_adb_static_command_copied)
    ItemPanel {
        val tcpPolicy = viewModel.config.adbTcpPolicy
        val configuredTcpPort = viewModel.config.tcpPort
        val paired = state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val activeTcpPort = state.tcpModePort
        val staticTcpActive = activeTcpPort != null
        val staticTcpConfigured = state.configuredTcpModePort != null
        val runtimeStartInProgress = state.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE
        val staticTcpOwnsRuntimeStart = runtimeStartInProgress &&
            state.runtimeStartSource == PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP
        val wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported()
        val staticTcpStatus = staticTcpPanelStatus(
            tcpModeConfigured = staticTcpConfigured,
            tcpModeActive = staticTcpActive,
            status = state.tcpAuthorizationStatus,
        )
        val staticTcpCommand = privilegeUiStaticTcpOpenCommand(configuredTcpPort)
        val prepareActionVisible = !wirelessAdbSupported &&
            tcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED
        val prepareActionEnabled = prepareActionVisible &&
            !runtimeStartInProgress &&
            paired &&
            !staticTcpActive &&
            !state.busy
        val startAction = staticTcpStartAction(
            runtimeStartPhase = state.runtimeStartPhase,
            ownsRuntimeStart = staticTcpOwnsRuntimeStart,
        )
        val tcpStartActionEnabled = staticTcpActionEnabled(
            action = startAction,
            busy = state.busy,
            wirelessAdbSupported = wirelessAdbSupported,
            tcpModeConfigured = staticTcpConfigured,
        )
        val commandHelpVisible = staticTcpCommandHelpVisible(
            wirelessAdbSupported = wirelessAdbSupported,
        )
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_static),
            text = staticTcpStatus.displayText(),
            color = staticTcpStatus.displayColor(),
        )
        if (prepareActionVisible) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = prepareActionEnabled,
                onClick = viewModel::enableTcpMode,
            ) {
                Text(stringResource(R.string.priv_ui_adb_static_prepare_action))
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = tcpStartActionEnabled,
            onClick = {
                when (startAction) {
                    PrivilegeUiStartAction.START ->
                        viewModel.startStaticTcpAdb()
                    PrivilegeUiStartAction.CANCEL -> viewModel.stopCurrentStart()
                    PrivilegeUiStartAction.CANCELLING,
                    PrivilegeUiStartAction.NONE,
                    -> Unit
                }
            },
        ) {
            Text(
                stringResource(
                    staticTcpActionLabel(
                        action = startAction,
                    ),
                ),
            )
        }
        if (commandHelpVisible) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.priv_ui_adb_static_command_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CommandBlock(staticTcpCommand)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !runtimeStartInProgress && !state.busy,
                    onClick = {
                        viewModel.copyStaticTcpCommand(context)
                        showFeedback(copiedMessage)
                    },
                ) {
                    Text(stringResource(R.string.priv_ui_manual_copy_command))
                }
            }
        }
    }
}

@Composable
private fun AdbFingerprintRow(fingerprint: String?) {
    val fingerprintTextStyle = MaterialTheme.typography.labelMedium
    var fingerprintWrapped by remember(fingerprint) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.priv_ui_adb_key_fingerprint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = fingerprint ?: stringResource(R.string.priv_ui_adb_key_fingerprint_unavailable),
                style = if (fingerprintWrapped) {
                    fingerprintTextStyle.copy(fontSize = 9.sp)
                } else {
                    fingerprintTextStyle
                },
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (fingerprintWrapped) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Visible,
                autoSize = if (fingerprintWrapped) {
                    null
                } else {
                    TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = fingerprintTextStyle.fontSize,
                        stepSize = 0.5.sp,
                    )
                },
                onTextLayout = { result ->
                    fingerprintWrapped = if (fingerprintWrapped) {
                        result.lineCount > 1
                    } else {
                        result.didOverflowWidth
                    }
                },
            )
        }
    }
}

@Composable
private fun AdbStatusRow(
    label: String,
    text: String,
    color: Color,
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
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PrivilegeUiWirelessAdbPanelStatus.displayText(): String =
    when (this) {
        PrivilegeUiWirelessAdbPanelStatus.WIFI_REQUIRED ->
            stringResource(R.string.priv_ui_wireless_status_wifi_required)
        PrivilegeUiWirelessAdbPanelStatus.OFF -> stringResource(R.string.priv_ui_wireless_status_off)
        PrivilegeUiWirelessAdbPanelStatus.UNPAIRED -> stringResource(R.string.priv_ui_wireless_status_unpaired)
        PrivilegeUiWirelessAdbPanelStatus.PAIRABLE -> stringResource(R.string.priv_ui_wireless_status_pairable)
        PrivilegeUiWirelessAdbPanelStatus.PAIRED -> stringResource(R.string.priv_ui_wireless_status_paired)
    }

@Composable
private fun PrivilegeUiWirelessAdbPanelStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiWirelessAdbPanelStatus.PAIRED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiWirelessAdbPanelStatus.PAIRABLE -> MaterialTheme.colorScheme.primary
        PrivilegeUiWirelessAdbPanelStatus.WIFI_REQUIRED,
        PrivilegeUiWirelessAdbPanelStatus.OFF,
        PrivilegeUiWirelessAdbPanelStatus.UNPAIRED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayText(): String =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED ->
            stringResource(R.string.priv_ui_adb_static_port_unavailable)
        PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED ->
            stringResource(R.string.priv_ui_adb_static_service_stopped)
        PrivilegeUiStaticTcpPanelStatus.CHECKING -> stringResource(R.string.priv_ui_wireless_status_checking)
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_unauthorized)
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_authorized)
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiStaticTcpPanelStatus.CHECKING,
        PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED,
        PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED,
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
