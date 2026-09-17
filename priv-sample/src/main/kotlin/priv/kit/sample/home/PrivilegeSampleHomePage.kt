package priv.kit.sample.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import priv.kit.sample.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeSampleHomePage(
    serverRunning: Boolean,
    onOpenPrivilegeUi: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenDeviceFiles: () -> Unit,
    onOpenFileApi: () -> Unit,
    onOpenCommandApi: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Priv Kit",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
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
            ServerStatusRow(running = serverRunning)
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(R.string.sample_choose_the_surface_you_want_to_inspect),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPrivilegeUi,
            ) {
                Text(stringResource(R.string.sample_open_privilege_ui))
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenDebug,
            ) {
                Text(stringResource(R.string.sample_open_debug_tools))
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenFileApi,
            ) {
                Text(stringResource(R.string.sample_test_file_api))
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenDeviceFiles,
            ) {
                Text(stringResource(R.string.sample_browse_device_files))
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenCommandApi,
            ) {
                Text(stringResource(R.string.sample_test_command_api))
            }
        }
    }
}

@Composable
private fun ServerStatusRow(running: Boolean) {
    val colors = MaterialTheme.colorScheme
    val statusColor = if (running) colors.tertiary else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sample_server_status),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(statusColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (running) stringResource(R.string.sample_running) else stringResource(R.string.sample_stopped),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = statusColor,
            )
        }
    }
}
