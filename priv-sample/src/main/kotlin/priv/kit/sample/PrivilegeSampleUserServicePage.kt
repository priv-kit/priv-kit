package priv.kit.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UserServiceTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onBindDedicatedUserService: () -> Unit,
    onCallDedicatedUserService: () -> Unit,
    onStopDedicatedUserService: () -> Unit,
    onBindEmbeddedUserService: () -> Unit,
    onCallEmbeddedUserService: () -> Unit,
    onStopEmbeddedUserService: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = "Test UserService",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
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
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UserServicePanel(state)
        SectionTitle("Dedicated Process")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.dedicatedUserServiceBound) "Dedicated Bound" else "Bind Dedicated",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.dedicatedUserServiceBound,
                background = colors.actionPrimary,
                modifier = Modifier.weight(1f),
                onClick = onBindDedicatedUserService,
            )
            SampleAction(
                label = "Call",
                enabled = !state.busy && state.dedicatedUserServiceCached,
                background = colors.actionSuccess,
                modifier = Modifier.weight(1f),
                onClick = onCallDedicatedUserService,
            )
            SampleAction(
                label = "Stop",
                enabled = !state.busy && state.dedicatedUserServiceCached,
                background = colors.actionDanger,
                modifier = Modifier.weight(1f),
                onClick = onStopDedicatedUserService,
            )
        }
        SectionTitle("Embedded")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.embeddedUserServiceBound) "Embedded Bound" else "Bind Embedded",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.embeddedUserServiceBound,
                background = colors.actionAccent,
                modifier = Modifier.weight(1f),
                onClick = onBindEmbeddedUserService,
            )
            SampleAction(
                label = "Call",
                enabled = !state.busy && state.embeddedUserServiceCached,
                background = colors.actionSuccess,
                modifier = Modifier.weight(1f),
                onClick = onCallEmbeddedUserService,
            )
            SampleAction(
                label = "Stop",
                enabled = !state.busy && state.embeddedUserServiceCached,
                background = colors.actionDanger,
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
    val colors = sampleColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panelBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(
            label = "dedicated",
            value = state.userServiceReferenceText(
                bound = state.dedicatedUserServiceBound,
                cached = state.dedicatedUserServiceCached,
            ),
        )
        RuntimeInfoRow(
            label = "embedded",
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
                    color = colors.textMuted,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

private fun PrivilegeSampleScreenState.userServiceReferenceText(
    bound: Boolean,
    cached: Boolean,
): String =
    when {
        bound -> "bound"
        cached -> "cached"
        else -> "-"
    }
