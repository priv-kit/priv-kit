package priv.kit.sample.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.PrivilegeServerInfo
import priv.kit.ui.PrivilegeScaffold

@Composable
internal fun PrivilegeUiAuthorizationPage(
    onBackClick: () -> Unit,
    onHelpClick: () -> Unit,
    onConnected: (PrivilegeServerInfo) -> Unit,
    onNotificationPermissionRequired: ((Boolean) -> Unit) -> Unit,
    onNotificationPermissionDisposed: ((Boolean) -> Unit) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel = viewModel<PrivilegeSamplePrivilegeUiViewModel>(
        factory = remember(application) {
            PrivilegeSamplePrivilegeUiViewModelFactory(application)
        },
    )
    val permissionResultHandler = remember(viewModel) {
        { granted: Boolean -> viewModel.handleNotificationPermissionResult(granted) }
    }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {}
    DisposableEffect(permissionResultHandler) {
        onDispose {
            onNotificationPermissionDisposed(permissionResultHandler)
        }
    }

    PrivilegeScaffold(
        modifier = Modifier.fillMaxSize(),
        viewModel = viewModel,
        onBackClick = onBackClick,
        onHelpClick = onHelpClick,
        onConnected = onConnected,
        onNotificationPermissionRequired = {
            onNotificationPermissionRequired(permissionResultHandler)
        },
        onLocalNetworkPermissionRequired = { permission ->
            localNetworkPermissionLauncher.launch(permission)
        },
    )
}

private class PrivilegeSamplePrivilegeUiViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PrivilegeSamplePrivilegeUiViewModel::class.java)) {
            return PrivilegeSamplePrivilegeUiViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
