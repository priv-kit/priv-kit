package priv.kit.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenScope
import priv.kit.ui.R

@Composable
internal fun PrivilegeUiScreenScope.RootPanel() {
    Panel {
        val action = state.startActionFor(PrivilegeUiRuntimeStartSource.ROOT)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.startActionEnabled(
                action = action,
                startAvailable = interactionEnabled,
            ),
            onClick = {
                when (action) {
                    PrivilegeUiStartAction.START -> viewModel.startRoot()
                    PrivilegeUiStartAction.CANCEL -> viewModel.stopCurrentStart()
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
                        startLabel = R.string.priv_ui_root_authorization_action,
                    ),
                ),
            )
        }
    }
}

@Composable
internal fun PrivilegeUiScreenScope.ManualShellPanel() {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.priv_ui_manual_command_copied)
    Panel {
        Text(
            text = stringResource(R.string.priv_ui_manual_authorization_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val commandLine = state.manualShellCommandLine
        if (commandLine == null) {
            Text(
                text = stringResource(R.string.priv_ui_manual_command_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CommandBlock(commandLine)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = interactionEnabled && !state.busy,
                onClick = {
                    if (!viewModel.uiInteractionsEnabled) return@Button
                    viewModel.copyManualCommand(context)
                    showFeedback(copiedMessage)
                },
            ) {
                Text(stringResource(R.string.priv_ui_manual_copy_command))
            }
        }
    }
}
