package priv.kit.ui.m3

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import priv.kit.ui.*
import priv.kit.ui.m3.component.PrivilegeTopBar

/** Default Material 3 page. All platform setup is supplied by the theme-independent UI module. */
@Composable
public fun PrivilegeM3Page(
    modifier: Modifier = Modifier,
    viewModel: PrivilegeM3ViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
    onMessage: ((String) -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable (SnackbarHostState) -> Unit = { SnackbarHost(it) },
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onViewPermissionSolutions: (() -> Unit)? = null,
    startupTutorialUrl: String? = null,
    onViewStartupTutorial: ((PrivilegeUiTutorialTopic) -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ui = rememberPrivilegeUi(
        viewModel = viewModel,
        onBack = onBack,
        onMessage = { message ->
            if (onMessage != null) onMessage(message)
            else scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        },
    )
    PrivilegeM3Presentation(viewModel)
    PrivilegeM3Screen(
        state = ui.state,
        actions = ui.actions,
        m3State = viewModel.m3State,
        m3Actions = viewModel.m3Actions,
        interactionEnabled = ui.interactionEnabled,
        showFeedback = ui.showMessage,
        modifier = modifier,
        topBar = topBar ?: { PrivilegeTopBar(onBack = ui.onBack) },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        snackbarHostState = snackbar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
        onViewPermissionSolutions = onViewPermissionSolutions,
        startupTutorialUrl = startupTutorialUrl,
        onViewStartupTutorial = onViewStartupTutorial,
    )
}
