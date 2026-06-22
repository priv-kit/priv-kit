package priv.kit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun RootPanel(
    state: PrivilegeUiState,
    onStartRoot: () -> Unit,
) {
    Panel {
        Text(
            text = stringResource(R.string.priv_ui_root_authorization_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            onClick = onStartRoot,
        ) {
            Text(stringResource(R.string.priv_ui_root_authorization_action))
        }
    }
}

@Composable
internal fun ManualShellPanel(
    state: PrivilegeUiState,
    onCopyCommand: () -> Unit,
) {
    Panel {
        Text(
            text = stringResource(R.string.priv_ui_manual_authorization_title),
            style = MaterialTheme.typography.titleMedium,
        )
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
            ManualCommandBlock(commandLine)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                onClick = onCopyCommand,
            ) {
                Text(stringResource(R.string.priv_ui_manual_copy_command))
            }
        }
    }
}

@Composable
private fun ManualCommandBlock(commandLine: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .background(
                color = Color(0xFF111820),
                shape = MaterialTheme.shapes.small,
            )
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text = commandLine,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF7FAFC),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
