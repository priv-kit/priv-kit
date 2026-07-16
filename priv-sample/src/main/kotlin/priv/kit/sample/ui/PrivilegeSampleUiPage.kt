package priv.kit.sample.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.sample.PrivilegeSamplePrivilegeUiCallbacks
import priv.kit.ui.PrivilegeScaffold

@Composable
internal fun PrivilegeUiAuthorizationPage(
    viewModel: PrivilegeSamplePrivilegeUiViewModel,
) {
    PrivilegeScaffold(
        modifier = Modifier.fillMaxSize(),
        viewModel = viewModel,
    )
}

@Composable
internal fun rememberPrivilegeSampleUiViewModel(
    host: PrivilegeSamplePrivilegeUiCallbacks,
): PrivilegeSamplePrivilegeUiViewModel {
    val application = LocalContext.current.applicationContext as Application
    return viewModel(
        factory = remember(application, host) {
            PrivilegeSamplePrivilegeUiViewModelFactory(application, host)
        },
    )
}

private class PrivilegeSamplePrivilegeUiViewModelFactory(
    private val application: Application,
    private val host: PrivilegeSamplePrivilegeUiCallbacks,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PrivilegeSamplePrivilegeUiViewModel::class.java)) {
            return PrivilegeSamplePrivilegeUiViewModel(application, host) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
