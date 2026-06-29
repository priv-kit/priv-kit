package priv.kit.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import priv.kit.PrivilegeServerInfo
import priv.kit.ui.PrivilegeScaffold
import priv.kit.ui.PrivilegeUiViewModel

@Composable
internal fun PrivilegeUiAuthorizationPage(
    viewModel: PrivilegeUiViewModel,
    onBackClick: () -> Unit,
    onHelpClick: () -> Unit,
    onConnected: (PrivilegeServerInfo) -> Unit,
    onNotificationPermissionRequired: () -> Unit,
) {
    PrivilegeScaffold(
        modifier = Modifier.fillMaxSize(),
        viewModel = viewModel,
        onBackClick = onBackClick,
        onHelpClick = onHelpClick,
        onConnected = onConnected,
        onNotificationPermissionRequired = onNotificationPermissionRequired,
    )
}
