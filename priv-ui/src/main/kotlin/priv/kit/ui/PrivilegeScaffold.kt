package priv.kit.ui

import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import priv.kit.ui.component.AdbPanel
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
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val permissionState = activity?.let {
                privilegeUiPermissionState(it, POST_NOTIFICATIONS_PERMISSION)
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
                    val permissionState = activity?.let {
                        privilegeUiPermissionState(it, POST_NOTIFICATIONS_PERMISSION)
                    }
                    if (
                        permissionState == null ||
                        permissionState.shouldLaunchPermissionRequest()
                    ) {
                        markPrivilegeUiPermissionRequested(POST_NOTIFICATIONS_PERMISSION)
                        notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
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
    DisposableEffect(viewModel, context) {
        val lifecycleOwner = context.findLifecycleOwner()
        val lifecycle = lifecycleOwner?.lifecycle
        if (lifecycle == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.onHostResume()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
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
            screenScope.AuthorizationModeTabs()
            screenScope.AuthorizationModePanel()
            if (state.startupLogLines.isNotEmpty()) {
                screenScope.StartupLogPanel()
            }
        }
    }
}

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

internal class PrivilegeUiScreenScope(
    internal val state: PrivilegeUiState,
    internal val viewModel: PrivilegeUiViewModel,
    internal val showFeedback: (String) -> Unit,
)

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.findLifecycleOwner()
        else -> null
    }

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
