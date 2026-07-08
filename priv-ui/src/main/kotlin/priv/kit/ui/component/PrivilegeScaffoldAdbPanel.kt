package priv.kit.ui.component

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus
import priv.kit.ui.PrivilegeUiRuntimeStatus
import priv.kit.ui.PrivilegeUiState
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.R
import priv.kit.ui.adb.PrivilegeUiAdbStartupTab
import priv.kit.ui.adb.PrivilegeUiStaticTcpPanelStatus
import priv.kit.ui.adb.PrivilegeUiWirelessAdbPanelStatus
import priv.kit.ui.adb.PrivilegeUiWirelessAdbStartAction
import priv.kit.ui.adb.defaultPrivilegeUiAdbStartupTab
import priv.kit.ui.adb.privilegeUiAdbStartupTabs
import priv.kit.ui.adb.privilegeUiWirelessAdbStartAction
import priv.kit.ui.adb.privilegeUiWirelessAdbStartActionEnabled
import priv.kit.ui.adb.privilegeUiWirelessAdbStartActionLabel
import priv.kit.ui.adb.staticTcpActionEnabled
import priv.kit.ui.adb.staticTcpActionLabel
import priv.kit.ui.adb.staticTcpActionVisible
import priv.kit.ui.adb.staticTcpCommandHelpVisible
import priv.kit.ui.adb.staticTcpPanelStatus
import priv.kit.ui.adb.wirelessAdbPanelStatus
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingCode
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand

@Composable
internal fun AdbPanel(
    state: PrivilegeUiState,
    selectedTab: PrivilegeUiAdbStartupTab?,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    managedWirelessAdbEnabled: Boolean,
    configuredTcpPort: Int,
    onTabSelected: (PrivilegeUiAdbStartupTab) -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onClosePairing: () -> Unit,
    onSubmitPairingCode: () -> Unit,
    onEnableTcpMode: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onStopWirelessAdb: () -> Unit,
    onStartStaticTcpAdb: () -> Unit,
    onCopyStaticTcpCommand: () -> Unit,
) {
    Panel {
        val paired = state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val wirelessStatus = wirelessAdbPanelStatus(
            wifiConnected = state.wifiConnected,
            wirelessDebuggingStatus = state.wirelessDebuggingStatus,
            wirelessPairingServiceStatus = state.wirelessPairingServiceStatus,
            wirelessPairingCheckStatus = state.wirelessPairingCheckStatus,
        )
        val managedWirelessReady = managedWirelessAdbEnabled &&
            state.managedWirelessAdbStatus == PrivilegeUiManagedWirelessAdbStatus.READY
        val wirelessStartPrerequisiteAvailable = paired ||
            state.wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON ||
            managedWirelessReady
        val wirelessStartAvailable = state.wifiConnected && wirelessStartPrerequisiteAvailable
        val staticTcpActive = state.tcpModePort != null
        val tabs = privilegeUiAdbStartupTabs(
            wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
            tcpPolicy = tcpPolicy,
        )
        val currentTab = selectedTab.takeIf { it in tabs }
            ?: defaultPrivilegeUiAdbStartupTab(
                tabs = tabs,
                tcpModeEnabled = staticTcpActive,
                tcpAuthorizationStatus = state.tcpAuthorizationStatus,
            )
        LaunchedEffect(tabs, currentTab, selectedTab) {
            if (currentTab != null && currentTab != selectedTab) {
                onTabSelected(currentTab)
            }
        }
        AdbFingerprintRow(fingerprint = state.adbKeyFingerprint)
        if (tabs.size > 1 && currentTab != null) {
            AdbStartupTabRow(
                tabs = tabs,
                selectedTab = currentTab,
                onSelected = onTabSelected,
            )
        }
        when (currentTab) {
            PrivilegeUiAdbStartupTab.WIRELESS -> {
                WirelessAdbSection(
                    state = state,
                    wirelessStatus = wirelessStatus,
                    startPrerequisiteAvailable = wirelessStartPrerequisiteAvailable,
                    startAvailable = wirelessStartAvailable,
                    onPairingCodeChanged = onPairingCodeChanged,
                    onStartPairing = onStartPairing,
                    onStopPairing = onStopPairing,
                    onClosePairing = onClosePairing,
                    onSubmitPairingCode = onSubmitPairingCode,
                    onStartWirelessAdb = onStartWirelessAdb,
                    onStopWirelessAdb = onStopWirelessAdb,
                )
            }
            PrivilegeUiAdbStartupTab.STATIC_TCP -> {
                StaticTcpAdbSection(
                    state = state,
                    paired = paired,
                    tcpPolicy = tcpPolicy,
                    configuredTcpPort = configuredTcpPort,
                    onEnableTcpMode = onEnableTcpMode,
                    onStartStaticTcpAdb = onStartStaticTcpAdb,
                    onStopStaticTcpAdb = onStopWirelessAdb,
                    onCopyStaticTcpCommand = onCopyStaticTcpCommand,
                )
            }
            null -> {
                StatusText(stringResource(R.string.priv_ui_adb_unavailable))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdbStartupTabRow(
    tabs: List<PrivilegeUiAdbStartupTab>,
    selectedTab: PrivilegeUiAdbStartupTab,
    onSelected: (PrivilegeUiAdbStartupTab) -> Unit,
) {
    val selectedIndex = tabs.indexOf(selectedTab).takeIf { it >= 0 } ?: 0
    SecondaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelected(tab) },
                text = {
                    Text(
                        text = stringResource(tab.labelRes()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun WirelessAdbSection(
    state: PrivilegeUiState,
    wirelessStatus: PrivilegeUiWirelessAdbPanelStatus,
    startPrerequisiteAvailable: Boolean,
    startAvailable: Boolean,
    onPairingCodeChanged: (String) -> Unit,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onClosePairing: () -> Unit,
    onSubmitPairingCode: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onStopWirelessAdb: () -> Unit,
) {
    ItemPanel {
        val wirelessAdbPaired = state.wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON &&
            state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val pairingActionEnabled = !wirelessAdbPaired &&
            (!state.busy || state.notificationPairingRunning)
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_wireless),
            text = wirelessStatus.displayText(),
            color = wirelessStatus.displayColor(),
        )
        if (state.pairingDialogVisible) {
            WirelessAdbPairingDialog(
                state = state,
                onPairingCodeChanged = onPairingCodeChanged,
                onSubmitPairingCode = onSubmitPairingCode,
                onStopPairing = onStopPairing,
                onClose = onClosePairing,
            )
        }
        val startAction = privilegeUiWirelessAdbStartAction(
            runtimeStatus = state.runtimeStatus,
            wifiConnected = state.wifiConnected,
            startPrerequisiteAvailable = startPrerequisiteAvailable,
            startAvailable = startAvailable,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = pairingActionEnabled,
                onClick = {
                    onStartPairing()
                },
            ) {
                Text(stringResource(R.string.priv_ui_wireless_pair_action))
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = privilegeUiWirelessAdbStartActionEnabled(
                    action = startAction,
                    busy = state.busy,
                    runtimeStatus = state.runtimeStatus,
                ),
                onClick = {
                    when (startAction) {
                        PrivilegeUiWirelessAdbStartAction.START -> onStartWirelessAdb()
                        PrivilegeUiWirelessAdbStartAction.INTERRUPT -> onStopWirelessAdb()
                        PrivilegeUiWirelessAdbStartAction.NONE -> Unit
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
private fun WirelessAdbPairingDialog(
    state: PrivilegeUiState,
    onPairingCodeChanged: (String) -> Unit,
    onSubmitPairingCode: () -> Unit,
    onStopPairing: () -> Unit,
    onClose: () -> Unit,
) {
    val defaultPairingMessage = stringResource(R.string.priv_ui_pairing_default_message)
    val pairing = state.pairingStatus == PrivilegeUiAdbPairingStatus.PAIRING
    val canSubmit = state.notificationPairingRunning &&
        !pairing &&
        state.pairingCode.isPrivilegeUiPairingCode()
    fun dismissOrStop() {
        if (pairing) {
            onStopPairing()
        } else {
            if (state.notificationPairingRunning) {
                onStopPairing()
            }
            onClose()
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
                    onValueChange = onPairingCodeChanged,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) {
                                onSubmitPairingCode()
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
                onClick = onSubmitPairingCode,
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
private fun StaticTcpAdbSection(
    state: PrivilegeUiState,
    paired: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    configuredTcpPort: Int,
    onEnableTcpMode: () -> Unit,
    onStartStaticTcpAdb: () -> Unit,
    onStopStaticTcpAdb: () -> Unit,
    onCopyStaticTcpCommand: () -> Unit,
) {
    ItemPanel {
        val activeTcpPort = state.tcpModePort
        val staticTcpActive = activeTcpPort != null
        val wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported()
        val staticTcpStatus = staticTcpPanelStatus(
            tcpModeEnabled = staticTcpActive,
            status = state.tcpAuthorizationStatus,
        )
        val staticTcpCommand = privilegeUiStaticTcpOpenCommand(configuredTcpPort)
        val startActionVisible = staticTcpActionVisible(
            tcpModeEnabled = staticTcpActive,
            runtimeStatus = state.runtimeStatus,
            status = state.tcpAuthorizationStatus,
            wirelessAdbSupported = wirelessAdbSupported,
        )
        val commandHelpVisible = staticTcpCommandHelpVisible(
            tcpModeEnabled = staticTcpActive,
            status = state.tcpAuthorizationStatus,
            wirelessAdbSupported = wirelessAdbSupported,
        )
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_static),
            text = staticTcpStatus.displayText(),
            color = staticTcpStatus.displayColor(),
        )
        AnimatedVisibility(
            visible = !wirelessAdbSupported &&
                paired &&
                !staticTcpActive &&
                tcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED,
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                onClick = onEnableTcpMode,
            ) {
                Text(stringResource(R.string.priv_ui_adb_static_prepare_action))
            }
        }
        AnimatedVisibility(visible = startActionVisible) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = startActionVisible && staticTcpActionEnabled(
                    tcpModeEnabled = staticTcpActive,
                    busy = state.busy,
                    runtimeStatus = state.runtimeStatus,
                    status = state.tcpAuthorizationStatus,
                    wirelessAdbSupported = wirelessAdbSupported,
                ),
                onClick = {
                    if (state.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING) {
                        onStopStaticTcpAdb()
                    } else {
                        onStartStaticTcpAdb()
                    }
                },
            ) {
                Text(
                    stringResource(
                        staticTcpActionLabel(
                            runtimeStatus = state.runtimeStatus,
                        ),
                    ),
                )
            }
        }
        AnimatedVisibility(visible = commandHelpVisible) {
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
                    enabled = !state.busy,
                    onClick = onCopyStaticTcpCommand,
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
        PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE -> stringResource(R.string.priv_ui_adb_static_port_unavailable)
        PrivilegeUiStaticTcpPanelStatus.CHECKING -> stringResource(R.string.priv_ui_wireless_status_checking)
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_unauthorized)
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_authorized)
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiStaticTcpPanelStatus.CHECKING,
        PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE,
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun PrivilegeUiAdbStartupTab.labelRes(): Int =
    when (this) {
        PrivilegeUiAdbStartupTab.WIRELESS -> R.string.priv_ui_adb_tab_wireless
        PrivilegeUiAdbStartupTab.STATIC_TCP -> R.string.priv_ui_adb_tab_static
    }
