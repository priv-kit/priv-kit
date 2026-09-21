package priv.kit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.resources.*
import priv.kit.ui.adb.PrivilegeUiStaticTcpPanelStatus
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchAction
import priv.kit.ui.adb.staticTcpActionLabel
import priv.kit.ui.adb.staticTcpStartActionEnabled
import priv.kit.ui.adb.staticTcpCommandHelpVisible
import priv.kit.ui.adb.staticTcpPanelStatus
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand

@Composable
internal fun PrivilegeUiScreenScope.TcpAuthorizationFailureDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(stringResource(Res.string.priv_ui_system_prompt_tcp_authorization_title))
        },
        text = {
            Text(stringResource(Res.string.priv_ui_tcp_authorization_timeout_message))
        },
        confirmButton = {
            TextButton(
                enabled = interactionEnabled,
                onClick = actions.dismissTcpAuthorizationFailureDialog,
            ) {
                Text(stringResource(Res.string.priv_ui_ok))
            }
        },
    )
}

@Composable
internal fun PrivilegeUiScreenScope.StaticTcpSwitchConfirmationDialog(
    action: PrivilegeUiStaticTcpSwitchAction,
) {
    AlertDialog(
        onDismissRequest = {
            if (interactionEnabled) actions.cancelStaticTcpSwitch()
        },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(stringResource(Res.string.priv_ui_adb_static_switch_confirmation_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium),
            ) {
                Text(
                    text = stringResource(Res.string.priv_ui_adb_static_switch_confirmation_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AdbConnectionWarning()
            }
        },
        confirmButton = {
            TextButton(
                enabled = interactionEnabled,
                onClick = actions.confirmStaticTcpSwitch,
            ) {
                Text(
                    stringResource(
                        when (action) {
                            PrivilegeUiStaticTcpSwitchAction.START_SERVICE ->
                                Res.string.priv_ui_adb_static_switch_continue_start_action
                            PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT ->
                                Res.string.priv_ui_adb_static_switch_continue_enable_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = interactionEnabled,
                onClick = actions.cancelStaticTcpSwitch,
            ) {
                Text(stringResource(Res.string.priv_ui_adb_static_switch_cancel_action))
            }
        },
    )
}

@Composable
internal fun PrivilegeUiScreenScope.StaticTcpAdbSection() {
    val adbInteractionEnabled = interactionEnabled
    val copiedMessage = stringResource(Res.string.priv_ui_adb_static_command_copied)
    var controlDialogVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium),
    ) {
        val tcpPolicy = state.adbTcpPolicy
        val configuredTcpPort = state.tcpPort
        val paired = state.wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
        val activeTcpPort = state.staticTcp.activePort
        val staticTcpActive = activeTcpPort != null
        val staticTcpConfigured = state.staticTcp.configuredPort != null
        val runtimeStartInProgress = state.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE
        val wirelessAdbSupported = state.wirelessAdbSupported
        val staticTcpStatus = staticTcpPanelStatus(
            tcpModeConfigured = staticTcpConfigured,
            tcpModeActive = staticTcpActive,
            status = state.staticTcp.authorizationStatus,
        )
        val staticTcpCommand = privilegeUiStaticTcpOpenCommand(
            activeTcpPort ?: configuredTcpPort,
        )
        val prepareActionVisible = !wirelessAdbSupported &&
            tcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED
        val prepareActionEnabled = prepareActionVisible &&
            state.staticTcp.loaded &&
            adbInteractionEnabled &&
            !runtimeStartInProgress &&
            !state.busy &&
            paired &&
            !staticTcpActive
        val startAction = state.startActionFor(
            source = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
            providerId = null,
        )
        val tcpStartActionEnabled = state.staticTcpStartActionEnabled(
            action = startAction,
            wirelessAdbSupported = wirelessAdbSupported,
            interactionEnabled = adbInteractionEnabled,
        )
        val controlActionEnabled = adbInteractionEnabled &&
            !runtimeStartInProgress &&
            !state.busy &&
            staticTcpActive
        val commandHelpVisible = staticTcpCommandHelpVisible(
            wirelessAdbSupported = wirelessAdbSupported,
        )
        AdbStatusRow(
            label = stringResource(Res.string.priv_ui_adb_tab_static),
            text = if (state.staticTcp.loaded) {
                staticTcpStatus.displayText()
            } else {
                stringResource(Res.string.priv_ui_status_loading)
            },
            color = if (state.staticTcp.loaded) {
                staticTcpStatus.displayColor()
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (prepareActionVisible) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = prepareActionEnabled,
                onClick = actions.enableTcpMode,
            ) {
                Text(stringResource(Res.string.priv_ui_adb_static_prepare_action))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = controlActionEnabled,
                onClick = {
                    controlDialogVisible = true
                },
            ) {
                Text(stringResource(Res.string.priv_ui_adb_static_control_action))
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = tcpStartActionEnabled,
                onClick = {
                    when (startAction) {
                        PrivilegeUiStartAction.START ->
                            actions.startStaticTcpAdb()
                        PrivilegeUiStartAction.CANCEL -> actions.stopCurrentStart()
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
        }
        if (commandHelpVisible) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.priv_ui_adb_static_command_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CommandBlock(staticTcpCommand)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = adbInteractionEnabled &&
                        !runtimeStartInProgress &&
                        !state.busy,
                    onClick = {
                        if (!actions.canInteract()) return@OutlinedButton
                        actions.copyStaticTcpCommand()
                        showFeedback(copiedMessage)
                    },
                ) {
                    Text(stringResource(Res.string.priv_ui_manual_copy_command))
                }
            }
        }
        if (controlDialogVisible) {
            StaticTcpControlDialog(
                commandLine = staticTcpCommand,
                commandVisible = commandHelpVisible,
                actionEnabled = controlActionEnabled,
                onDismiss = {
                    controlDialogVisible = false
                },
                onStop = {
                    controlDialogVisible = false
                    actions.disableTcpMode()
                },
                onRestart = {
                    controlDialogVisible = false
                    actions.restartTcpMode()
                },
            )
        }
    }
}

@Composable
private fun StaticTcpControlDialog(
    commandLine: String,
    commandVisible: Boolean,
    actionEnabled: Boolean,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(stringResource(Res.string.priv_ui_adb_static_control_dialog_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium),
            ) {
                if (commandVisible) {
                    Text(
                        text = stringResource(Res.string.priv_ui_adb_static_command_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CommandBlock(commandLine)
                }
                AdbConnectionWarning()
            }
        },
        confirmButton = {
            TextButton(
                enabled = actionEnabled,
                onClick = onRestart,
            ) {
                Text(stringResource(Res.string.priv_ui_adb_static_control_restart_action))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.priv_ui_adb_static_switch_cancel_action))
                }
                TextButton(
                    enabled = actionEnabled,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = onStop,
                ) {
                    Text(stringResource(Res.string.priv_ui_adb_static_control_stop_action))
                }
            }
        },
    )
}

@Composable
private fun AdbConnectionWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PrivilegeUiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = PrivilegeUiIcons.Warning,
                    contentDescription = null,
                )
                Spacer(Modifier.width(PrivilegeUiSpacing.medium))
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.priv_ui_adb_connection_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(Res.string.priv_ui_adb_connection_warning_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayText(): String =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED ->
            stringResource(Res.string.priv_ui_adb_static_port_unavailable)
        PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED ->
            stringResource(Res.string.priv_ui_adb_static_service_stopped)
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED -> stringResource(Res.string.priv_ui_adb_static_status_unauthorized)
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> stringResource(Res.string.priv_ui_adb_static_status_authorized)
    }

@Composable
private fun PrivilegeUiStaticTcpPanelStatus.displayColor(): Color =
    when (this) {
        PrivilegeUiStaticTcpPanelStatus.AUTHORIZED -> MaterialTheme.colorScheme.tertiary
        PrivilegeUiStaticTcpPanelStatus.PORT_NOT_CONFIGURED,
        PrivilegeUiStaticTcpPanelStatus.ADB_SERVICE_STOPPED,
        PrivilegeUiStaticTcpPanelStatus.UNAUTHORIZED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
