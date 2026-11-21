package priv.kit.sample.startup

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import priv.kit.ui.PrivilegeUiHost
import priv.kit.ui.m3.PrivilegeM3Screen
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.m3.component.PrivilegeTopBar

@Composable
internal fun PrivilegeUiAuthorizationPage(
    viewModel: PrivilegeSamplePrivilegeUiViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    PrivilegeUiHost(
        viewModel = viewModel,
        onBack = onBack,
        onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    ) { ui ->
        priv.kit.ui.m3.PrivilegeM3Presentation(viewModel)
        PrivilegeM3Screen(
            modifier = Modifier.fillMaxSize(),
            state = ui.state,
            actions = ui.actions,
            m3State = viewModel.m3State,
            m3Actions = viewModel.m3Actions,
            interactionEnabled = ui.interactionEnabled,
            showFeedback = ui.showMessage,
            topBar = { PrivilegeTopBar(onBack = ui.onBack) },
        )
    }
}

@Composable
internal fun rememberPrivilegeSampleUiViewModel(
    config: PrivilegeUiConfig,
): PrivilegeSamplePrivilegeUiViewModel {
    val application = LocalContext.current.applicationContext as Application
    return viewModel { PrivilegeSamplePrivilegeUiViewModel(application, config) }
}
