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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import priv.kit.sample.R

@Composable
internal fun PermissionsTestPage(
    state: PrivilegeSampleScreenState,
    callbacks: PrivilegeSampleDebugCallbacks,
) {
    val colors = MaterialTheme.colorScheme
    SamplePageScaffold(
        title = stringResource(R.string.sample_test_permissions),
        selectedDestination = PrivilegeSampleDebugDestination.Permissions,
        busy = state.busy,
        onDestinationSelected = callbacks.destinationSelected,
        onBackToHome = callbacks.backToHome,
        actions = {},
    ) {
        StatusPanel(state, callbacks.connection.stopServer)
        BasicText(
            text = stringResource(R.string.sample_denied_server_permissions) + (state.deniedPermissions?.let { " (${it.size})" } ?: ""),
            style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        val text = when {
            state.serverInfo == null -> stringResource(R.string.sample_connect_to_a_privileged_server_to_query_permissions)
            state.deniedPermissionsLoading -> stringResource(R.string.sample_loading_denied_permissions)
            state.deniedPermissionsError != null -> stringResource(R.string.sample_query_failed, state.deniedPermissionsError)
            state.deniedPermissions == null -> stringResource(R.string.sample_permissions_have_not_been_queried)
            state.serverInfo.uid == 0 -> stringResource(R.string.sample_root_mode_this_api_returns_an_empty_list)
            state.deniedPermissions.isEmpty() -> stringResource(R.string.sample_no_denied_manifest_permissions_found)
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
            text = stringResource(R.string.sample_permissions_description),
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp),
        )
        SampleAction(
            label = stringResource(R.string.sample_refresh_permissions),
            enabled = state.serverInfo != null && !state.deniedPermissionsLoading,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = callbacks.connection.refreshDeniedPermissions,
        )
    }
}
