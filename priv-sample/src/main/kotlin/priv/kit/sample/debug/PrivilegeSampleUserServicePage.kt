package priv.kit.sample.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.sample.R

@Composable
internal fun UserServiceTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDebugDestination,
    onDestinationSelected: (PrivilegeSampleDebugDestination) -> Unit,
    onBackToHome: () -> Unit,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = stringResource(R.string.sample_test_userservice),
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
        onBackToHome = onBackToHome,
        actions = {},
    ) {
        StatusPanel(state, onStopServer)
        UserServicePage(
            state = state,
            onBindDedicatedUserService = onBindDedicatedUserService,
            onCallDedicatedUserService = onCallDedicatedUserService,
            onStopDedicatedUserService = onStopDedicatedUserService,
            onBindEmbeddedUserService = onBindEmbeddedUserService,
            onCallEmbeddedUserService = onCallEmbeddedUserService,
            onStopEmbeddedUserService = onStopEmbeddedUserService,
        )
    }
}

@Composable
private fun UserServicePage(
    state: PrivilegeSampleScreenState,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UserServicePanel(state)
        SectionTitle(stringResource(R.string.sample_dedicated_process))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.dedicatedUserServiceBound) stringResource(R.string.sample_dedicated_bound) else stringResource(R.string.sample_bind_dedicated),
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.dedicatedUserServiceBound,
                tone = SampleActionTone.Primary,
                modifier = Modifier.weight(1f),
                onClick = onBindDedicatedUserService,
            )
            SampleAction(
                label = stringResource(R.string.sample_call),
                enabled = !state.busy && state.dedicatedUserServiceCached,
                tone = SampleActionTone.Secondary,
                modifier = Modifier.weight(1f),
                onClick = onCallDedicatedUserService,
            )
            SampleAction(
                label = stringResource(R.string.sample_stop),
                enabled = !state.busy && state.dedicatedUserServiceCached,
                tone = SampleActionTone.Destructive,
                modifier = Modifier.weight(1f),
                onClick = onStopDedicatedUserService,
            )
        }
        SectionTitle(stringResource(R.string.sample_embedded))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.embeddedUserServiceBound) stringResource(R.string.sample_embedded_bound) else stringResource(R.string.sample_bind_embedded),
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.embeddedUserServiceBound,
                tone = SampleActionTone.Secondary,
                modifier = Modifier.weight(1f),
                onClick = onBindEmbeddedUserService,
            )
            SampleAction(
                label = stringResource(R.string.sample_call),
                enabled = !state.busy && state.embeddedUserServiceCached,
                tone = SampleActionTone.Primary,
                modifier = Modifier.weight(1f),
                onClick = onCallEmbeddedUserService,
            )
            SampleAction(
                label = stringResource(R.string.sample_stop),
                enabled = !state.busy && state.embeddedUserServiceCached,
                tone = SampleActionTone.Destructive,
                modifier = Modifier.weight(1f),
                onClick = onStopEmbeddedUserService,
            )
        }
        if (state.userServiceLastException.isNotBlank()) {
            DiagnosticBlock(state.userServiceLastException)
        }
    }
}

@Composable
private fun UserServicePanel(state: PrivilegeSampleScreenState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(
            label = stringResource(R.string.sample_dedicated),
            value = state.userServiceReferenceText(
                bound = state.dedicatedUserServiceBound,
                cached = state.dedicatedUserServiceCached,
            ),
        )
        RuntimeInfoRow(
            label = stringResource(R.string.sample_embedded),
            value = state.userServiceReferenceText(
                bound = state.embeddedUserServiceBound,
                cached = state.embeddedUserServiceCached,
            ),
        )
        SelectionContainer {
            BasicText(
                text = buildString {
                    appendLine(state.userServiceMessage)
                    appendLine("dedicated: ${state.dedicatedUserServiceMessage}")
                    append("embedded: ${state.embeddedUserServiceMessage}")
                },
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

@Composable
private fun PrivilegeSampleScreenState.userServiceReferenceText(
    bound: Boolean,
    cached: Boolean,
): String =
    when {
        bound -> stringResource(R.string.sample_bound)
        cached -> stringResource(R.string.sample_cached)
        else -> "-"
    }
