package priv.kit.sample.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.sample.R

@Composable
internal fun BinderTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDebugDestination,
    onDestinationSelected: (PrivilegeSampleDebugDestination) -> Unit,
    onBackToHome: () -> Unit,
    onSystemServiceNameChanged: (String) -> Unit,
    onCheckSystemService: () -> Unit,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = stringResource(R.string.sample_test_binder),
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
        onBackToHome = onBackToHome,
        actions = {},
    ) {
        StatusPanel(state, onStopServer)
        BinderPage(
            state = state,
            onSystemServiceNameChanged = onSystemServiceNameChanged,
            onCheckSystemService = onCheckSystemService,
            onGetUserManager = onGetUserManager,
            onGetUsers = onGetUsers,
            onRunImqsNative = onRunImqsNative,
        )
    }
}

@Composable
private fun BinderPage(
    state: PrivilegeSampleScreenState,
    onSystemServiceNameChanged: (String) -> Unit,
    onCheckSystemService: () -> Unit,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SystemServiceCheckPanel(
            state = state,
            onSystemServiceNameChanged = onSystemServiceNameChanged,
            onCheckSystemService = onCheckSystemService,
        )
        SectionTitle(stringResource(R.string.sample_binder_smoke_tests))
        BinderStatusPanel(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.userManagerCached) stringResource(R.string.sample_iusermanager_cached) else stringResource(R.string.sample_get_iusermanager),
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.userManagerCached,
                tone = SampleActionTone.Tonal,
                modifier = Modifier.weight(1f),
                onClick = onGetUserManager,
            )
            SampleAction(
                label = stringResource(R.string.sample_get_users),
                enabled = !state.busy &&
                    (state.status == PrivilegeSampleStatus.CONNECTED || state.userManagerCached),
                tone = SampleActionTone.Primary,
                modifier = Modifier.weight(1f),
                onClick = onGetUsers,
            )
        }
        SampleAction(
            label = stringResource(R.string.sample_probe_imqsnative),
            enabled = !state.busy &&
                (state.status == PrivilegeSampleStatus.CONNECTED || state.systemServiceBinderCached),
            tone = SampleActionTone.Secondary,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRunImqsNative,
        )
        if (state.binderLastException.isNotBlank()) {
            DiagnosticBlock(state.binderLastException)
        }
    }
}

@Composable
private fun SystemServiceCheckPanel(
    state: PrivilegeSampleScreenState,
    onSystemServiceNameChanged: (String) -> Unit,
    onCheckSystemService: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.sample_system_service_availability))
        BasicText(
            text = stringResource(R.string.sample_check_the_same_service_name_from_this_app_process_and_the_privileged_server),
            style = TextStyle(
                color = colors.onSurfaceVariant,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        )
        SampleField(
            label = stringResource(R.string.sample_servicename),
            value = state.systemServiceNameText,
            onValueChange = onSystemServiceNameChanged,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            enabled = !state.busy,
        )
        SampleAction(
            label = stringResource(R.string.sample_check_both_processes),
            enabled = !state.busy && state.systemServiceNameText.isNotBlank(),
            tone = SampleActionTone.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCheckSystemService,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeInfoRow(
                label = stringResource(R.string.sample_current_process),
                value = systemServiceStatusText(
                    presence = state.systemServiceCheckResult?.currentProcess,
                ),
            )
            RuntimeInfoRow(
                label = stringResource(R.string.sample_privileged_process),
                value = systemServiceStatusText(
                    presence = state.systemServiceCheckResult?.serverProcess,
                ),
            )
        }
    }
}

@Composable
private fun systemServiceStatusText(
    presence: PrivilegeSampleSystemServicePresence?,
): String =
    when {
        presence == null -> stringResource(R.string.sample_not_checked)
        presence.error != null -> stringResource(R.string.sample_unavailable)
        presence.exists == true -> stringResource(R.string.sample_exists)
        presence.exists == false -> stringResource(R.string.sample_missing)
        else -> stringResource(R.string.sample_not_checked)
    }

@Composable
private fun BinderStatusPanel(state: PrivilegeSampleScreenState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(label = "IMQSNative", value = if (state.systemServiceBinderCached) stringResource(R.string.sample_cached) else "-")
        RuntimeInfoRow(
            label = stringResource(R.string.sample_imqs_local),
            value = state.mqsNativeLocalDescriptor ?: state.mqsNativeLocalError ?: "-",
        )
        RuntimeInfoRow(
            label = stringResource(R.string.sample_imqs_remote),
            value = state.mqsNativeRemoteDescriptor ?: state.mqsNativeRemoteError ?: "-",
        )
        RuntimeInfoRow(label = "IUserManager", value = if (state.userManagerCached) stringResource(R.string.sample_cached) else "-")
        SelectionContainer {
            BasicText(
                text = state.binderMessage,
                style = TextStyle(
                    color = colors.onSurfaceVariant,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}
