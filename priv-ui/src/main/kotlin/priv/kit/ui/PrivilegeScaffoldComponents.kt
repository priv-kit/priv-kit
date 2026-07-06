package priv.kit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import priv.kit.PrivilegeServerInfo

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun ItemPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun CommandBlock(commandLine: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF111820),
                shape = MaterialTheme.shapes.small,
            )
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = commandLine,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF7FAFC),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun ServiceStatusPanel(
    state: PrivilegeUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    var showStopConfirmation by remember { mutableStateOf(false) }
    val running = state.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
    val (
        title,
        detail,
        background,
        foreground,
        icon,
        iconDescription,
        actionContainer,
        actionForeground,
    ) = if (running) {
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
    } else {
        StatusUi(
            title = stringResource(R.string.priv_ui_service_not_started),
            detail = state.runtimeStatusDetail(),
            background = MaterialTheme.colorScheme.surface,
            foreground = MaterialTheme.colorScheme.onSurface,
            icon = PrivilegeUiIcons.PlayArrow,
            iconDescription = stringResource(R.string.priv_ui_service_start_action_description),
            actionContainer = MaterialTheme.colorScheme.primaryContainer,
            actionForeground = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = {
                Text(stringResource(R.string.priv_ui_stop_service_dialog_title))
            },
            text = {
                Text(stringResource(R.string.priv_ui_stop_service_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirmation = false
                        onStopClick()
                    },
                ) {
                    Text(stringResource(R.string.priv_ui_stop_service_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text(stringResource(R.string.priv_ui_stop_service_cancel))
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
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
                    color = foreground.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            PrivilegeIconTooltip(text = iconDescription) {
                FilledTonalIconButton(
                    modifier = Modifier.size(44.dp),
                    enabled = !state.busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = actionContainer,
                        contentColor = actionForeground,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                    ),
                    onClick = {
                        if (running) {
                            showStopConfirmation = true
                        } else {
                            onStartClick()
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

@Composable
internal fun StartupLogPanel(
    lines: List<String>,
    onCopyLog: () -> Unit,
) {
    Panel {
        val copyLogDescription = stringResource(R.string.priv_ui_startup_log_copy_description)
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
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            enabled = lines.isNotEmpty(),
                            onClickLabel = copyLogDescription,
                            role = Role.Button,
                            onClick = onCopyLog,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = PrivilegeUiIcons.ContentCopy,
                        contentDescription = copyLogDescription,
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
                    color = Color(0xFF101820),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(12.dp),
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
                    color = Color(0xFFF7FAFC),
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
    when (runtimeStatus) {
        PrivilegeUiRuntimeStatus.STARTING -> runtimeProgressMessage
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.priv_ui_ready)
        PrivilegeUiRuntimeStatus.DISCONNECTED,
        PrivilegeUiRuntimeStatus.FAILED,
        PrivilegeUiRuntimeStatus.CONNECTED,
        -> stringResource(R.string.priv_ui_ready)
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
