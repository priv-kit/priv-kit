package priv.kit.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import priv.kit.ui.component.RootPanel
import priv.kit.ui.component.ServiceStatusPanel
import priv.kit.ui.component.StartupLogPanel

@Composable
public fun PrivilegeScaffold(
    modifier: Modifier = Modifier,
    viewModel: PrivilegeUiViewModel = viewModel(),
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val state by viewModel.state.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::handleNotificationPermissionResult,
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
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }
    val screenScope = PrivilegeUiScreenScope(
        state = state,
        viewModel = viewModel,
        backDispatcher = backDispatcher,
        showFeedback = ::showFeedback,
    )
    LaunchedEffect(state.connectionSerial) {
        val serverInfo = state.serverInfo
        if (state.connectionSerial > 0L && serverInfo != null) {
            viewModel.dispatchConnected(state.connectionSerial, serverInfo)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.permissionRequests.collect { request ->
            when (request) {
                PrivilegeUiPermissionRequest.Notification ->
                    notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                is PrivilegeUiPermissionRequest.LocalNetwork ->
                    localNetworkPermissionLauncher.launch(request.permission)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
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
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        topBar = {
            screenScope.PrivilegeTopBar()
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
    internal val backDispatcher: OnBackPressedDispatcher?,
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
