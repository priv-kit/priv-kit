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
import androidx.compose.material3.OutlinedTextField
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

private val PairingInlineIconButtonSize = 56.dp

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
                    paired = paired,
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
    paired: Boolean,
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
        RealtimeStatusRow(
            label = stringResource(R.string.priv_ui_wireless_status_debugging),
            status = state.wirelessDebuggingStatus,
        )
        AnimatedVisibility(
            visible = state.wirelessPairingCheckStatus != PrivilegeUiWirelessAdbStatus.ON,
        ) {
            RealtimeStatusRow(
                label = stringResource(R.string.priv_ui_wireless_status_pairing_service),
                status = state.wirelessPairingServiceStatus,
                negativeLabel = stringResource(R.string.priv_ui_status_unavailable),
            )
        }
        RealtimeStatusRow(
            label = stringResource(R.string.priv_ui_wireless_status_pairing_check),
            status = state.wirelessPairingCheckStatus,
            positiveLabel = stringResource(R.string.priv_ui_wireless_status_yes),
            negativeLabel = stringResource(R.string.priv_ui_wireless_status_no),
        )
        AnimatedVisibility(visible = !paired) {
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
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = state.pairingCode,
                        onValueChange = onPairingCodeChanged,
                        singleLine = true,
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
        val staticTcpCommand = privilegeUiStaticTcpOpenCommand(configuredTcpPort)
        val startActionVisible = staticTcpActionVisible(staticTcpActive)
        val commandHelpVisible = staticTcpCommandHelpVisible(staticTcpActive)
        StaticTcpPortStatusRow(
            tcpPort = activeTcpPort,
        )
        TcpAuthorizationStatusRow(
            status = staticTcpAuthorizationDisplayStatus(
                tcpModeEnabled = staticTcpActive,
                status = state.tcpAuthorizationStatus,
            ),
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
private fun StaticTcpPortStatusRow(
    tcpPort: Int?,
) {
    val enabled = tcpPort != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (tcpPort != null) {
                stringResource(R.string.priv_ui_adb_static_port_status, tcpPort)
            } else {
                stringResource(R.string.priv_ui_adb_static_port_status_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (enabled) {
                    R.string.priv_ui_adb_static_port_ready
                } else {
                    R.string.priv_ui_adb_static_port_unavailable
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
            text = stringResource(R.string.priv_ui_adb_static_authorization_status),
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
        PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> stringResource(R.string.priv_ui_adb_static_port_unavailable)
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
        PrivilegeUiRuntimeStatus.CONNECTED,
        PrivilegeUiRuntimeStatus.STARTING,
        -> PrivilegeUiWirelessAdbStartAction.STOP
        PrivilegeUiRuntimeStatus.DISCONNECTED,
        PrivilegeUiRuntimeStatus.FAILED,
        -> when {
            startAvailable -> PrivilegeUiWirelessAdbStartAction.START
            startPrerequisiteAvailable && !wifiConnected -> PrivilegeUiWirelessAdbStartAction.WIFI_REQUIRED
            else -> PrivilegeUiWirelessAdbStartAction.NONE
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
    tcpModeEnabled &&
        !busy &&
        status != PrivilegeUiAdbTcpAuthorizationStatus.CHECKING

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

private fun PrivilegeUiAdbStartupTab.labelRes(): Int =
    when (this) {
        PrivilegeUiAdbStartupTab.WIRELESS -> R.string.priv_ui_adb_tab_wireless
        PrivilegeUiAdbStartupTab.STATIC_TCP -> R.string.priv_ui_adb_tab_static
    }
