package priv.kit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PairingInlineIconButtonSize = 40.dp

@Composable
internal fun AdbPanel(
    state: PrivilegeUiState,
    selectedTab: PrivilegeUiAdbStartupTab?,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    managedWirelessAdbEnabled: Boolean,
    configuredTcpPort: Int,
    onTabSelected: (PrivilegeUiAdbStartupTab) -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onPairByCode: () -> Unit,
    onCancelPairing: () -> Unit,
    onNotificationPairingClick: () -> Unit,
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
                    onPairByCode = onPairByCode,
                    onCancelPairing = onCancelPairing,
                    onNotificationPairingClick = onNotificationPairingClick,
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
    onPairByCode: () -> Unit,
    onCancelPairing: () -> Unit,
    onNotificationPairingClick: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onStopWirelessAdb: () -> Unit,
) {
    ItemPanel {
        val showPairingControls = wirelessStatus != PrivilegeUiWirelessAdbPanelStatus.PAIRED &&
            state.wirelessPairingCheckStatus != PrivilegeUiWirelessAdbStatus.ON
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_wireless),
            text = wirelessStatus.displayText(),
            color = wirelessStatus.displayColor(),
        )
        AnimatedVisibility(visible = showPairingControls) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val pairingActionEnabled = !state.busy || state.wirelessPairingRunning
                val pairingActionLabel = stringResource(
                    if (state.wirelessPairingRunning) {
                        R.string.priv_ui_wireless_pair_searching_action
                    } else {
                        R.string.priv_ui_wireless_pair_action
                    },
                )
                val notificationActionEnabled = !state.busy || state.notificationPairingRunning
                val notificationActionLabel = stringResource(
                    if (state.notificationPairingRunning) {
                        R.string.priv_ui_notification_pairing_waiting
                    } else {
                        R.string.priv_ui_pair_via_notification
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PairingSquareIconButton(
                        enabled = notificationActionEnabled,
                        primary = false,
                        imageVector = if (state.notificationPairingRunning) {
                            PrivilegeUiIcons.NotificationsActive
                        } else {
                            PrivilegeUiIcons.Notifications
                        },
                        contentDescription = notificationActionLabel,
                        onClick = onNotificationPairingClick,
                    )
                    PairingCodeTextField(
                        modifier = Modifier.weight(1f),
                        value = state.pairingCode,
                        onValueChange = onPairingCodeChanged,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (pairingActionEnabled && !state.wirelessPairingRunning) {
                                    onPairByCode()
                                }
                            },
                        ),
                        label = { Text(stringResource(R.string.priv_ui_pairing_code)) },
                    )
                    PairingSquareIconButton(
                        enabled = pairingActionEnabled,
                        primary = true,
                        imageVector = if (state.wirelessPairingRunning) {
                            PrivilegeUiIcons.Stop
                        } else {
                            PrivilegeUiIcons.Check
                        },
                        contentDescription = pairingActionLabel,
                        onClick = {
                            if (state.wirelessPairingRunning) {
                                onCancelPairing()
                            } else {
                                onPairByCode()
                            }
                        },
                    )
                }
            }
        }
        val startAction = privilegeUiWirelessAdbStartAction(
            runtimeStatus = state.runtimeStatus,
            wifiConnected = state.wifiConnected,
            startPrerequisiteAvailable = startPrerequisiteAvailable,
            startAvailable = startAvailable,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = privilegeUiWirelessAdbStartActionEnabled(
                action = startAction,
                busy = state.busy,
                runtimeStatus = state.runtimeStatus,
            ),
            onClick = {
                when (startAction) {
                    PrivilegeUiWirelessAdbStartAction.START -> onStartWirelessAdb()
                    PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED -> onStartWirelessAdb()
                    PrivilegeUiWirelessAdbStartAction.STOP -> onStopWirelessAdb()
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

@Composable
private fun PairingSquareIconButton(
    enabled: Boolean,
    primary: Boolean,
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    PrivilegeIconTooltip(text = contentDescription) {
        Surface(
            modifier = Modifier
                .size(PairingInlineIconButtonSize)
                .clickable(
                    enabled = enabled,
                    onClickLabel = contentDescription,
                    role = Role.Button,
                    onClick = onClick,
                ),
            shape = MaterialTheme.shapes.small,
            color = when {
                !enabled -> colors.onSurface.copy(alpha = 0.08f)
                primary -> colors.primary
                else -> colors.surface
            },
            contentColor = when {
                !enabled -> colors.onSurface.copy(alpha = 0.34f)
                primary -> colors.onPrimary
                else -> colors.primary
            },
            border = if (primary) {
                null
            } else {
                BorderStroke(
                    width = 1.dp,
                    color = if (enabled) {
                        colors.outline
                    } else {
                        colors.onSurface.copy(alpha = 0.12f)
                    },
                )
            },
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = contentDescription,
                )
            }
        }
    }
}

@Composable
private fun StaticTcpAdbSection(
    state: PrivilegeUiState,
    paired: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    configuredTcpPort: Int,
    onEnableTcpMode: () -> Unit,
    onStartStaticTcpAdb: () -> Unit,
    onCopyStaticTcpCommand: () -> Unit,
) {
    ItemPanel {
        val activeTcpPort = state.tcpModePort
        val staticTcpActive = activeTcpPort != null
        val staticTcpStatus = staticTcpPanelStatus(
            tcpModeEnabled = staticTcpActive,
            status = state.tcpAuthorizationStatus,
        )
        val staticTcpCommand = privilegeUiStaticTcpOpenCommand(configuredTcpPort)
        val startActionVisible = staticTcpActionVisible(staticTcpActive)
        val commandHelpVisible = staticTcpCommandHelpVisible(staticTcpActive)
        AdbStatusRow(
            label = stringResource(R.string.priv_ui_adb_tab_static),
            text = staticTcpStatus.displayText(),
            color = staticTcpStatus.displayColor(),
        )
        AnimatedVisibility(
            visible = paired &&
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
                    status = state.tcpAuthorizationStatus,
                ),
                onClick = onStartStaticTcpAdb,
            ) {
                Text(
                    stringResource(
                        staticTcpActionLabel(
                            tcpModeEnabled = staticTcpActive,
                            status = state.tcpAuthorizationStatus,
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
        PrivilegeUiWirelessAdbPanelStatus.OFF,
        PrivilegeUiWirelessAdbPanelStatus.UNPAIRED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayText(): String =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE -> stringResource(R.string.priv_ui_adb_static_port_unavailable)
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_unauthorized)
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> stringResource(R.string.priv_ui_adb_static_status_authorized)
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiStaticTcpPanelStatus.UNAVAILABLE,
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun PrivilegeUiAdbStartupTab.labelRes(): Int =
    when (this) {
        PrivilegeUiAdbStartupTab.WIRELESS -> R.string.priv_ui_adb_tab_wireless
        PrivilegeUiAdbStartupTab.STATIC_TCP -> R.string.priv_ui_adb_tab_static
    }
