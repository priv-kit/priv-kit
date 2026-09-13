package priv.kit.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.resources.*

@Composable
internal fun PrivilegeUiScreenScope.RootPanel() {
    Panel {
        val action = state.startActionFor(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.startActionEnabled(
                action = action,
                startAvailable = interactionEnabled,
            ),
            onClick = {
                when (action) {
                    PrivilegeUiStartAction.START -> actions.startRoot()
                    PrivilegeUiStartAction.CANCEL -> actions.stopCurrentStart()
                    PrivilegeUiStartAction.CANCELLING,
                    PrivilegeUiStartAction.NONE,
                    -> Unit
                }
            },
        ) {
            Text(
                stringResource(
                    privilegeUiStartActionLabel(
                        action = action,
                        startLabel = Res.string.priv_ui_root_authorization_action,
                    ),
                ),
            )
        }
    }
}

@Composable
internal fun PrivilegeUiScreenScope.ManualShellPanel() {
    val copiedMessage = stringResource(Res.string.priv_ui_manual_command_copied)
    Panel {
        Text(
            text = stringResource(Res.string.priv_ui_manual_authorization_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val commandLine = state.manualShellCommandLine
        if (commandLine == null) {
            Text(
                text = stringResource(Res.string.priv_ui_manual_command_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CommandBlock(commandLine)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = interactionEnabled && !state.busy,
                onClick = {
                    if (!actions.canInteract()) return@Button
                    actions.copyManualCommand()
                    showFeedback(copiedMessage)
                },
            ) {
                Text(stringResource(Res.string.priv_ui_manual_copy_command))
            }
        }
    }
}
