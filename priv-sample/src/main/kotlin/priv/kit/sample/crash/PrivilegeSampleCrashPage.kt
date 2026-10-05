package priv.kit.sample.crash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import priv.kit.sample.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeSampleCrashPage(
    serverRunning: Boolean,
    viewModel: PrivilegeSampleCrashViewModel,
    onBack: () -> Unit,
) {
    val selected = viewModel.selected
    val back = { if (selected != null) viewModel.closeDetails() else onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(serverRunning) { viewModel.refresh(serverRunning) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (selected == null) R.string.sample_crash_logs else R.string.sample_crash_details)) },
                navigationIcon = {
                    TextButton(onClick = back) {
                        Text(stringResource(if (selected == null) R.string.sample_home else R.string.sample_crash_logs))
                    }
                },
                actions = {
                    if (selected == null) {
                        TextButton(enabled = !viewModel.loading, onClick = { viewModel.refresh(serverRunning) }) {
                            Text(stringResource(R.string.sample_refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) {
                item { CrashDetails(selected) }
            } else {
                if (viewModel.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!serverRunning) item {
                    Text(stringResource(R.string.sample_crash_fallback_disconnected), style = MaterialTheme.typography.bodyMedium)
                }
                items(viewModel.errors) { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                if (!viewModel.loading && viewModel.entries.isEmpty()) item {
                    Text(stringResource(R.string.sample_crash_empty))
                }
                items(viewModel.entries, key = { it.path }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { viewModel.select(entry) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                entry.report?.exceptionType?.substringAfterLast('.') ?: stringResource(R.string.sample_crash_unreadable),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(crashTime(entry.report?.crashedAtEpochMillis ?: entry.modifiedAt))
                            entry.report?.let { report ->
                                Text("${report.processType} · UID ${report.uid} · PID ${report.pid}", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                entry.error ?: entry.report?.exceptionMessage ?: stringResource(R.string.sample_crash_no_message),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (entry.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashDetails(entry: PrivilegeSampleCrashEntry) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sample_crash_file), style = MaterialTheme.typography.titleMedium)
            Text(entry.path, style = MaterialTheme.typography.bodySmall)
            val report = entry.report
            if (report == null) {
                Text(stringResource(R.string.sample_crash_unreadable), style = MaterialTheme.typography.titleMedium)
                Text(entry.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else {
                Text(report.exceptionType, style = MaterialTheme.typography.titleMedium)
                Text(report.exceptionMessage ?: stringResource(R.string.sample_crash_no_message))
                Text(stringResource(R.string.sample_crash_occurred_at, crashTime(report.crashedAtEpochMillis)))
                Text(stringResource(R.string.sample_crash_started_at, crashTime(report.startedAtEpochMillis)))
                Text("applicationId: ${report.applicationId}\nuserId: ${report.userId}\nUID: ${report.uid}\nPID: ${report.pid}\nprocessType: ${report.processType}\nthreadName: ${report.threadName}\nschemaVersion: ${report.schemaVersion}")
                report.serviceClassName?.let { Text("serviceClassName: $it") }
                Text(stringResource(R.string.sample_crash_stack_trace), style = MaterialTheme.typography.titleMedium)
                Text(report.stackTrace, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun crashTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
