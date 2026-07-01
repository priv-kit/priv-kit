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
internal fun BinderTestPage(
    state: PrivilegeSampleScreenState,
    selectedDestination: PrivilegeSampleDestination,
    onDestinationSelected: (PrivilegeSampleDestination) -> Unit,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
    onStopServer: () -> Unit,
) {
    SamplePageScaffold(
        title = "Test Binder",
        selectedDestination = selectedDestination,
        busy = state.busy,
        onDestinationSelected = onDestinationSelected,
    ) {
        StatusPanel(state, onStopServer)
        BinderPage(
            state = state,
            onGetUserManager = onGetUserManager,
            onGetUsers = onGetUsers,
            onRunImqsNative = onRunImqsNative,
        )
    }
}

@Composable
private fun BinderPage(
    state: PrivilegeSampleScreenState,
    onGetUserManager: () -> Unit,
    onGetUsers: () -> Unit,
    onRunImqsNative: () -> Unit,
) {
    val colors = sampleColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BinderStatusPanel(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleAction(
                label = if (state.userManagerCached) "IUserManager Cached" else "Get IUserManager",
                enabled = !state.busy &&
                    state.status == PrivilegeSampleStatus.CONNECTED &&
                    !state.userManagerCached,
                background = colors.actionWarning,
                modifier = Modifier.weight(1f),
                onClick = onGetUserManager,
            )
            SampleAction(
                label = "Get Users",
                enabled = !state.busy &&
                    (state.status == PrivilegeSampleStatus.CONNECTED || state.userManagerCached),
                background = colors.actionSuccess,
                modifier = Modifier.weight(1f),
                onClick = onGetUsers,
            )
        }
        SampleAction(
            label = "Probe IMQSNative",
            enabled = !state.busy &&
                (state.status == PrivilegeSampleStatus.CONNECTED || state.systemServiceBinderCached),
            background = colors.actionAccent,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRunImqsNative,
        )
        if (state.binderLastException.isNotBlank()) {
            DiagnosticBlock(state.binderLastException)
        }
    }
}

@Composable
private fun BinderStatusPanel(state: PrivilegeSampleScreenState) {
    val colors = sampleColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panelBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeInfoRow(label = "IMQSNative", value = if (state.systemServiceBinderCached) "cached" else "-")
        RuntimeInfoRow(
            label = "IMQS Local",
            value = state.mqsNativeLocalDescriptor ?: state.mqsNativeLocalError ?: "-",
        )
        RuntimeInfoRow(
            label = "IMQS Remote",
            value = state.mqsNativeRemoteDescriptor ?: state.mqsNativeRemoteError ?: "-",
        )
        RuntimeInfoRow(label = "IUserManager", value = if (state.userManagerCached) "cached" else "-")
        SelectionContainer {
            BasicText(
                text = state.binderMessage,
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
