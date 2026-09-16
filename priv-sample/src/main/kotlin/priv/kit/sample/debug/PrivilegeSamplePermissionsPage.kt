package priv.kit.sample.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PermissionsTestPage(
    state: PrivilegeSampleScreenState,
    callbacks: PrivilegeSampleDebugCallbacks,
) {
    val colors = MaterialTheme.colorScheme
    SamplePageScaffold(
        title = "Test Permissions",
        selectedDestination = PrivilegeSampleDebugDestination.Permissions,
        busy = state.busy,
        onDestinationSelected = callbacks.destinationSelected,
        onBackToHome = callbacks.backToHome,
        actions = {},
    ) {
        StatusPanel(state, callbacks.connection.stopServer)
        BasicText(
            text = "Denied Server Permissions" + (state.deniedPermissions?.let { " (${it.size})" } ?: ""),
            style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        val text = when {
            state.serverInfo == null -> "Connect to a Privileged Server to query permissions."
            state.deniedPermissionsLoading -> "Loading denied permissions..."
            state.deniedPermissionsError != null -> "Query failed: ${state.deniedPermissionsError}"
            state.deniedPermissions == null -> "Permissions have not been queried."
            state.serverInfo.uid == 0 -> "Root mode: this API returns an empty list."
            state.deniedPermissions.isEmpty() -> "No denied manifest permissions found."
            else -> state.deniedPermissions.joinToString("\n")
        }
        SelectionContainer {
            BasicText(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerLow)
                    .padding(16.dp),
                style = TextStyle(
                    color = if (state.deniedPermissionsError != null) colors.error else colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
        BasicText(
            text = "Checks manifest permissions declared by packages associated with the server UID. " +
                "Does not include AppOps, SELinux, or service-specific restrictions. " +
                "An empty list does not guarantee every operation is available.",
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp),
        )
        SampleAction(
            label = "Refresh Permissions",
            enabled = state.serverInfo != null && !state.deniedPermissionsLoading,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = callbacks.connection.refreshDeniedPermissions,
        )
    }
}
