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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                        Text("Home")
                    }
                },
                title = {
                    Text(
                        text = "Test Command API",
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
                text = if (serverRunning) "Server connected" else "Server disconnected",
                color = if (serverRunning) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "This sample passes the text to /system/bin/sh -c. The Core API itself " +
                    "executes an argument list directly and does not add a shell.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.commandText,
                onValueChange = viewModel::updateCommand,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                minLines = 4,
                label = { Text("Shell command") },
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
                label = { Text("Timeout milliseconds (0 = none)") },
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = canStart,
                onClick = viewModel::runStreaming,
            ) {
                Text("Run with Streaming Output")
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = canStart,
                onClick = viewModel::runForResult,
            ) {
                Text("Run and Wait for Result")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isRunning,
                onClick = { viewModel.cancel() },
            ) {
                Text("Cancel")
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
                    Text("Clear Output")
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
            text = if (truncated) "$title (truncated)" else title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        SelectionContainer {
            Text(
                text = text.ifEmpty { "<empty>" },
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
