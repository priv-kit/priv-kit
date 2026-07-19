package priv.kit.ui

import android.Manifest
import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import priv.kit.ui.component.AdbPanel
import priv.kit.ui.component.AdbPermissionRestrictionWarning
import priv.kit.ui.component.AuthorizationModeTabs
import priv.kit.ui.component.ExternalStartPanel
import priv.kit.ui.component.ManualShellPanel
import priv.kit.ui.component.PrivilegeTopBar
import priv.kit.ui.component.PrivilegeUiSpacing
import priv.kit.ui.component.RootPanel
import priv.kit.ui.component.ServiceStatusPanel
import priv.kit.ui.component.StartupLogPanel

@Composable
public fun PrivilegeScaffold(
    modifier: Modifier = Modifier,
    viewModel: PrivilegeUiViewModel = viewModel(),
    topBar: @Composable () -> Unit = {
        PrivilegeTopBar(viewModel)
    },
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable (SnackbarHostState) -> Unit = {
        SnackbarHost(it)
    },
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationPermission = if (isPrivilegeUiNotificationPermissionSupported()) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val permissionState = activity?.let { currentActivity ->
                notificationPermission?.let { permission ->
                    privilegeUiPermissionState(currentActivity, permission)
                }
            } ?: if (granted) {
                PrivilegeUiPermissionState.Granted
            } else {
                PrivilegeUiPermissionState.NotGranted.Denied
            }
            viewModel.handleNotificationPermissionResult(permissionState)
        },
    )
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    fun showFeedback(message: String) {
        snackbarScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = message)
        }
    }
    val screenScope = PrivilegeUiScreenScope(
        state = state,
        viewModel = viewModel,
        showFeedback = ::showFeedback,
    )
    LaunchedEffect(state.connectionSerial) {
        val serverInfo = state.serverInfo
        if (state.connectionSerial > 0L && serverInfo != null) {
            viewModel.dispatchConnected(state.connectionSerial, serverInfo)
        }
    }
    LaunchedEffect(viewModel, activity) {
        viewModel.permissionRequests.collect { request ->
            when (request) {
                PrivilegeUiPermissionRequest.Notification -> {
                    val permission = notificationPermission ?: return@collect
                    val permissionState = activity?.let {
                        privilegeUiPermissionState(it, permission)
                    }
                    if (
                        permissionState == null ||
                        permissionState.shouldLaunchPermissionRequest()
                    ) {
                        markPrivilegeUiPermissionRequested(permission)
                        notificationPermissionLauncher.launch(permission)
                    } else {
                        viewModel.handleNotificationPermissionResult(permissionState)
                    }
                }
                is PrivilegeUiPermissionRequest.LocalNetwork -> {
                    markPrivilegeUiPermissionRequested(request.permission)
                    localNetworkPermissionLauncher.launch(request.permission)
                }
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = message)
        }
    }
    LifecycleEventEffect(
        event = Lifecycle.Event.ON_RESUME,
        lifecycleOwner = lifecycleOwner,
        onEvent = viewModel::dispatchHostResume,
    )
    DisposableEffect(viewModel, lifecycleOwner, view) {
        val lifecycle = lifecycleOwner.lifecycle
        val refreshObserver = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                viewModel.dispatchHostWindowFocus()
            }
        }
        val viewTreeObserver = view.viewTreeObserver
        viewTreeObserver.addOnWindowFocusChangeListener(refreshObserver)
        onDispose {
            val removalObserver = if (viewTreeObserver.isAlive) {
                viewTreeObserver
            } else {
                view.viewTreeObserver
            }
            if (removalObserver.isAlive) {
                removalObserver.removeOnWindowFocusChangeListener(refreshObserver)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = {
            snackbarHost(snackbarHostState)
        },
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PrivilegeUiSpacing.large,
                    top = PrivilegeUiSpacing.medium,
                    end = PrivilegeUiSpacing.large,
                    bottom = PrivilegeUiSpacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(PrivilegeUiSpacing.large),
        ) {
            screenScope.ServiceStatusPanel()
            screenScope.AdbPermissionRestrictionWarning()
            screenScope.AuthorizationModeTabs()
            screenScope.AuthorizationModePanel()
            if (state.startupLogLines.isNotEmpty()) {
                screenScope.StartupLogPanel()
            }
        }
    }
}

internal class PrivilegeUiScreenScope(
    val state: PrivilegeUiState,
    val viewModel: PrivilegeUiViewModel,
    val showFeedback: (String) -> Unit,
)

@Composable
private fun PrivilegeUiScreenScope.AuthorizationModePanel() {
    val mode = state.selectedStartupMode.takeIf { it in state.startupModes }
        ?: state.startupModes.first()
    when (mode) {
        PrivilegeUiStartupMode.ROOT -> RootPanel()
        PrivilegeUiStartupMode.MANUAL_SHELL -> ManualShellPanel()
        PrivilegeUiStartupMode.ADB -> AdbPanel()
        PrivilegeUiStartupMode.EXTERNAL -> ExternalStartPanel()
    }
}
