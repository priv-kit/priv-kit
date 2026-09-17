package priv.kit.sample.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import priv.kit.sample.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeSampleCommandPage(
    serverRunning: Boolean,
    viewModel: PrivilegeSampleCommandViewModel,
    onBackToHome: () -> Unit,
) {
    LaunchedEffect(serverRunning) {
        if (!serverRunning) viewModel.onServerDisconnected()
    }
    val state = viewModel.state
    val canStart = serverRunning && !state.isRunning

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBackToHome) {
                        Text(stringResource(R.string.sample_home))
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.sample_test_command_api),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (serverRunning) stringResource(R.string.sample_server_connected) else stringResource(R.string.sample_server_disconnected),
                color = if (serverRunning) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.sample_command_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.commandText,
                onValueChange = viewModel::updateCommand,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                minLines = 4,
                label = { Text(stringResource(R.string.sample_shell_command)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                supportingText = state.inputError?.let { error ->
                    { Text(error) }
                },
                isError = state.inputError != null,
            )
            OutlinedTextField(
                value = state.timeoutText,
                onValueChange = viewModel::updateTimeout,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                singleLine = true,
                label = { Text(stringResource(R.string.sample_timeout_milliseconds_0_none)) },
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = canStart,
                onClick = viewModel::runStreaming,
            ) {
                Text(stringResource(R.string.sample_run_with_streaming_output))
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = canStart,
                onClick = viewModel::runForResult,
            ) {
                Text(stringResource(R.string.sample_run_and_wait_for_result))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isRunning,
                onClick = { viewModel.cancel() },
            ) {
                Text(stringResource(R.string.sample_cancel))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                    enabled = !state.isRunning,
                    onClick = viewModel::clearOutput,
                ) {
                    Text(stringResource(R.string.sample_clear_output))
                }
            }
            CommandOutput(
                title = "stdout",
                text = state.stdout,
                truncated = state.stdoutTruncated,
            )
            CommandOutput(
                title = "stderr",
                text = state.stderr,
                truncated = state.stderrTruncated,
            )
        }
    }
}

@Composable
private fun CommandOutput(
    title: String,
    text: String,
    truncated: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (truncated) stringResource(R.string.sample_output_truncated, title) else title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        SelectionContainer {
            Text(
                text = text.ifEmpty { stringResource(R.string.sample_empty) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(16.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
