package priv.kit.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.PrivilegeUiAdbRestrictionStatus
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStatus
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.PrivilegeUiState
import priv.kit.ui.R

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(PrivilegeUiSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.medium),
            content = content,
        )
    }
}

@Composable
internal fun ItemPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(PrivilegeUiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.small),
            content = content,
        )
    }
}

@Composable
internal fun PrivilegeUiScreenScope.AdbPermissionRestrictionWarning() {
    if (
        !privilegeUiAdbPermissionRestrictionWarningVisible(
            runtimeStatus = state.runtimeStatus,
            restrictionStatus = state.adbRestrictionStatus,
        )
    ) {
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PrivilegeUiSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = PrivilegeUiIcons.Warning,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(PrivilegeUiSpacing.medium))
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.priv_ui_adb_restricted_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.priv_ui_adb_restricted_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun privilegeUiAdbPermissionRestrictionWarningVisible(
    runtimeStatus: PrivilegeUiRuntimeStatus,
    restrictionStatus: PrivilegeUiAdbRestrictionStatus,
): Boolean =
    runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED &&
        restrictionStatus == PrivilegeUiAdbRestrictionStatus.RESTRICTED

@Composable
internal fun CommandBlock(commandLine: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
            )
            .padding(PrivilegeUiSpacing.medium),
    ) {
        SelectionContainer {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = commandLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun PrivilegeUiScreenScope.AutoRecoveryWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PrivilegeUiSpacing.large,
                    vertical = PrivilegeUiSpacing.medium,
                ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall),
            ) {
                Text(
                    text = stringResource(R.string.priv_ui_auto_recovery_disconnected_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.priv_ui_auto_recovery_disconnected_message),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                modifier = Modifier.align(Alignment.End),
                enabled = interactionEnabled,
                onClick = viewModel::disableAutoRecovery,
            ) {
                Text(
                    text = stringResource(R.string.priv_ui_auto_recovery_disable_action),
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun privilegeUiAutoRecoveryWarningVisible(
    desiredEnabled: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
): Boolean =
    desiredEnabled &&
        runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE &&
        (
            runtimeStatus == PrivilegeUiRuntimeStatus.DISCONNECTED ||
                runtimeStatus == PrivilegeUiRuntimeStatus.FAILED
        )

@Composable
internal fun PrivilegeUiScreenScope.ServiceStatusPanel() {
    var showStopConfirmation by remember { mutableStateOf(false) }
    val action = privilegeUiServiceStatusAction(
        runtimeStatus = state.runtimeStatus,
        runtimeStartPhase = state.runtimeStartPhase,
    )
    val (
        title,
        detail,
        background,
        foreground,
        icon,
        iconDescription,
        actionContainer,
        actionForeground,
    ) = when (action) {
        PrivilegeUiServiceStatusAction.STOP ->
        StatusUi(
            title = stringResource(R.string.priv_ui_service_started),
            detail = stringResource(
                R.string.priv_ui_service_source,
                state.serverInfo.runtimeSourceText(),
            ),
            background = MaterialTheme.colorScheme.tertiaryContainer,
            foreground = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = PrivilegeUiIcons.Stop,
            iconDescription = stringResource(R.string.priv_ui_service_stop_action_description),
            actionContainer = MaterialTheme.colorScheme.errorContainer,
            actionForeground = MaterialTheme.colorScheme.onErrorContainer,
        )
        PrivilegeUiServiceStatusAction.CANCEL -> StatusUi(
            title = stringResource(R.string.priv_ui_service_not_started),
            detail = state.runtimeStatusDetail(),
            background = MaterialTheme.colorScheme.surfaceContainerLow,
            foreground = MaterialTheme.colorScheme.onSurface,
            icon = PrivilegeUiIcons.Stop,
            iconDescription = stringResource(R.string.priv_ui_start_cancel_action),
            actionContainer = MaterialTheme.colorScheme.errorContainer,
            actionForeground = MaterialTheme.colorScheme.onErrorContainer,
        )
        PrivilegeUiServiceStatusAction.CANCELLING -> StatusUi(
            title = stringResource(R.string.priv_ui_service_not_started),
            detail = state.runtimeStatusDetail(),
            background = MaterialTheme.colorScheme.surfaceContainerLow,
            foreground = MaterialTheme.colorScheme.onSurface,
            icon = PrivilegeUiIcons.Stop,
            iconDescription = stringResource(R.string.priv_ui_start_cancelling_action),
            actionContainer = MaterialTheme.colorScheme.errorContainer,
            actionForeground = MaterialTheme.colorScheme.onErrorContainer,
        )
        PrivilegeUiServiceStatusAction.START ->
        StatusUi(
            title = stringResource(R.string.priv_ui_service_not_started),
            detail = state.runtimeStatusDetail(),
            background = MaterialTheme.colorScheme.surfaceContainerLow,
            foreground = MaterialTheme.colorScheme.onSurface,
            icon = PrivilegeUiIcons.PlayArrow,
            iconDescription = stringResource(R.string.priv_ui_service_start_action_description),
            actionContainer = MaterialTheme.colorScheme.primaryContainer,
            actionForeground = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (interactionEnabled) showStopConfirmation = false
            },
            title = {
                Text(stringResource(R.string.priv_ui_stop_service_dialog_title))
            },
            text = {
                Text(stringResource(R.string.priv_ui_stop_service_dialog_message))
            },
            confirmButton = {
                TextButton(
                    enabled = interactionEnabled,
                    onClick = {
                        if (!viewModel.uiInteractionsEnabled) return@TextButton
                        showStopConfirmation = false
                        viewModel.stopServer()
                    },
                ) {
                    Text(stringResource(R.string.priv_ui_stop_service_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = interactionEnabled,
                    onClick = { showStopConfirmation = false },
                ) {
                    Text(stringResource(R.string.priv_ui_stop_service_cancel))
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PrivilegeUiSpacing.large,
                    top = PrivilegeUiSpacing.medium,
                    end = PrivilegeUiSpacing.medium,
                    bottom = PrivilegeUiSpacing.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.extraSmall),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(PrivilegeUiSpacing.medium))
            PrivilegeIconTooltip(text = iconDescription) {
                FilledTonalIconButton(
                    modifier = Modifier.size(PrivilegeUiSize.minimumTouchTarget),
                    enabled = interactionEnabled &&
                        privilegeUiServiceStatusActionEnabled(action, state.busy),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = actionContainer,
                        contentColor = actionForeground,
                    ),
                    onClick = {
                        if (!viewModel.uiInteractionsEnabled) return@FilledTonalIconButton
                        when (action) {
                            PrivilegeUiServiceStatusAction.STOP -> showStopConfirmation = true
                            PrivilegeUiServiceStatusAction.CANCEL -> viewModel.stopCurrentStart()
                            PrivilegeUiServiceStatusAction.CANCELLING -> Unit
                            PrivilegeUiServiceStatusAction.START -> viewModel.startInteractive()
                        }
                    },
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = icon,
                        contentDescription = iconDescription,
                    )
                }
            }
        }
    }
}

internal enum class PrivilegeUiServiceStatusAction {
    START,
    CANCEL,
    CANCELLING,
    STOP,
}

internal fun privilegeUiServiceStatusAction(
    runtimeStatus: PrivilegeUiRuntimeStatus,
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
): PrivilegeUiServiceStatusAction =
    when (runtimeStartPhase) {
        PrivilegeUiRuntimeStartPhase.RUNNING -> PrivilegeUiServiceStatusAction.CANCEL
        PrivilegeUiRuntimeStartPhase.CANCELLING -> PrivilegeUiServiceStatusAction.CANCELLING
        PrivilegeUiRuntimeStartPhase.IDLE -> when (runtimeStatus) {
            PrivilegeUiRuntimeStatus.CONNECTED -> PrivilegeUiServiceStatusAction.STOP
            PrivilegeUiRuntimeStatus.STARTING,
            PrivilegeUiRuntimeStatus.DISCONNECTED,
            PrivilegeUiRuntimeStatus.FAILED,
            -> PrivilegeUiServiceStatusAction.START
        }
    }

internal fun privilegeUiServiceStatusActionEnabled(
    action: PrivilegeUiServiceStatusAction,
    busy: Boolean,
): Boolean =
    when (action) {
        PrivilegeUiServiceStatusAction.CANCEL -> true
        PrivilegeUiServiceStatusAction.CANCELLING -> false
        PrivilegeUiServiceStatusAction.START,
        PrivilegeUiServiceStatusAction.STOP,
        -> !busy
    }

@Composable
internal fun PrivilegeUiScreenScope.StartupLogPanel() {
    val context = LocalContext.current
    val lines = state.startupLogLines
    val copiedMessage = stringResource(R.string.priv_ui_startup_log_copied)
    Panel {
        val copyLogDescription = stringResource(R.string.priv_ui_startup_log_copy_description)
        val closeLogDescription = stringResource(R.string.priv_ui_startup_log_close_description)
        val logScrollState = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.priv_ui_startup_log_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PrivilegeIconTooltip(text = copyLogDescription) {
                IconButton(
                    modifier = Modifier.size(PrivilegeUiSize.minimumTouchTarget),
                    enabled = interactionEnabled && lines.isNotEmpty(),
                    onClick = {
                        if (!viewModel.uiInteractionsEnabled) return@IconButton
                        viewModel.copyStartupLog(context)
                        showFeedback(copiedMessage)
                    },
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = PrivilegeUiIcons.ContentCopy,
                        contentDescription = copyLogDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrivilegeIconTooltip(text = closeLogDescription) {
                IconButton(
                    modifier = Modifier.size(PrivilegeUiSize.minimumTouchTarget),
                    enabled = interactionEnabled,
                    onClick = {
                        if (!viewModel.uiInteractionsEnabled) return@IconButton
                        viewModel.clearStartupLog()
                    },
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = PrivilegeUiIcons.Close,
                        contentDescription = closeLogDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 480.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(PrivilegeUiSpacing.medium),
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(logScrollState),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
internal fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PrivilegeUiExternalStartSnapshot.externalStartStatusText(): String =
    when {
        canStart -> stringResource(R.string.priv_ui_external_ready)
        available -> stringResource(R.string.priv_ui_external_permission_required)
        else -> stringResource(R.string.priv_ui_external_unavailable)
    }

private data class StatusUi(
    val title: String,
    val detail: String,
    val background: Color,
    val foreground: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconDescription: String,
    val actionContainer: Color,
    val actionForeground: Color,
)

@Composable
private fun PrivilegeUiState.runtimeStatusDetail(): String =
    when (runtimeStartPhase) {
        PrivilegeUiRuntimeStartPhase.CANCELLING -> stringResource(R.string.priv_ui_startup_cancelling)
        PrivilegeUiRuntimeStartPhase.RUNNING -> runtimeProgressMessage
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.priv_ui_ready)
        PrivilegeUiRuntimeStartPhase.IDLE -> when (runtimeStatus) {
            PrivilegeUiRuntimeStatus.STARTING -> runtimeProgressMessage
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.priv_ui_ready)
            PrivilegeUiRuntimeStatus.DISCONNECTED,
            PrivilegeUiRuntimeStatus.FAILED,
            PrivilegeUiRuntimeStatus.CONNECTED,
            -> stringResource(R.string.priv_ui_ready)
        }
    }

@Composable
private fun PrivilegeServerInfo?.runtimeSourceText(): String =
    when (this?.uid) {
        ROOT_UID -> stringResource(R.string.priv_ui_service_source_root)
        SHELL_UID -> stringResource(R.string.priv_ui_service_source_shell)
        else -> stringResource(R.string.priv_ui_service_source_unknown)
    }

private const val ROOT_UID = 0
private const val SHELL_UID = 2000
