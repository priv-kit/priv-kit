package priv.kit.ui

import android.Manifest
import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import priv.kit.ui.component.AdbPanel
import priv.kit.ui.component.PermissionRestrictionWarning
import priv.kit.ui.component.AuthorizationModeTabs
import priv.kit.ui.component.AutoRecoveryWarning
import priv.kit.ui.component.ExternalStartPanel
import priv.kit.ui.component.ManualShellPanel
import priv.kit.ui.component.PrivilegeSystemPromptOverlay
import priv.kit.ui.component.PrivilegeTopBar
import priv.kit.ui.component.PrivilegeUiSpacing
import priv.kit.ui.component.RootPanel
import priv.kit.ui.component.RestartConfirmationDialog
import priv.kit.ui.component.ServiceStatusPanel
import priv.kit.ui.component.StartupLogPanel
import priv.kit.ui.component.privilegeUiAutoRecoveryWarningVisible
import java.util.UUID

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
    val activity = LocalActivity.current!!
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val permissionHostId = rememberSaveable { UUID.randomUUID().toString() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startGateState by viewModel.startGateState.collectAsStateWithLifecycle()
    val visibleSystemPrompt by viewModel.visibleSystemPrompt.collectAsStateWithLifecycle()
    val interactionEnabled = viewModel.canInteract(startGateState)
    val notificationPermission = if (isPrivilegeUiNotificationPermissionSupported()) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val requestPermissionContract = remember { ActivityResultContracts.RequestPermission() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = requestPermissionContract,
        onResult = { granted ->
            val permissionState = notificationPermission?.let { permission ->
                privilegeUiPermissionState(activity, permission)
            } ?: if (granted) {
                PrivilegeUiPermissionState.Granted
            } else {
                PrivilegeUiPermissionState.NotGranted.Denied
            }
            viewModel.completeNotificationPermissionRequest(permissionHostId, permissionState)
        },
    )
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = requestPermissionContract,
        onResult = { granted ->
            viewModel.completeLocalNetworkPermissionRequest(
                hostId = permissionHostId,
                permissionState = if (granted) {
                    PrivilegeUiPermissionState.Granted
                } else {
                    PrivilegeUiPermissionState.NotGranted.Denied
                },
            )
        },
    )
    DisposableEffect(viewModel, permissionHostId, lifecycleOwner, view) {
        viewModel.registerPermissionHost(
            hostId = permissionHostId,
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            hasWindowFocus = view.hasWindowFocus(),
        )
        onDispose {
            viewModel.unregisterPermissionHost(
                hostId = permissionHostId,
                changingConfigurations = activity.isChangingConfigurations,
            )
        }
    }
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
        interactionEnabled = interactionEnabled,
        showFeedback = ::showFeedback,
    )
    screenScope.RestartConfirmationDialog()
    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { request ->
            when (request) {
                is PrivilegeUiPermissionRequest.Notification -> {
                    if (request.wasLaunched) {
                        request.awaitCompletion()
                    } else {
                        if (notificationPermission == null) {
                            viewModel.cancelPermissionRequest(permissionHostId, request)
                        } else {
                            val permissionState = privilegeUiPermissionState(activity, notificationPermission)
                            if (permissionState.shouldLaunchPermissionRequest()) {
                                if (request.tryMarkLaunched(permissionHostId)) {
                                    markPrivilegeUiPermissionRequested(notificationPermission)
                                    runCatching {
                                        notificationPermissionLauncher.launch(notificationPermission)
                                    }.onFailure {
                                        viewModel.cancelPermissionRequest(permissionHostId, request)
                                    }
                                }
                                request.awaitCompletion()
                            } else {
                                viewModel.completeUnlaunchedNotificationPermissionRequest(
                                    permissionHostId,
                                    request,
                                    permissionState,
                                )
                            }
                        }
                    }
                }
                is PrivilegeUiPermissionRequest.LocalNetwork -> {
                    if (request.tryMarkLaunched(permissionHostId)) {
                        markPrivilegeUiPermissionRequested(request.permission)
                        runCatching {
                            localNetworkPermissionLauncher.launch(request.permission)
                        }.onFailure {
                            viewModel.cancelPermissionRequest(permissionHostId, request)
                        }
                    }
                    request.awaitCompletion()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.snackbarTexts.collect { text ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = text.asString(context))
        }
    }
    LifecycleEventEffect(
        event = Lifecycle.Event.ON_RESUME,
        lifecycleOwner = lifecycleOwner,
        onEvent = {
            viewModel.dispatchHostResume(
                hostId = permissionHostId,
                hasWindowFocus = view.hasWindowFocus(),
            )
        },
    )
    LifecycleEventEffect(
        event = Lifecycle.Event.ON_PAUSE,
        lifecycleOwner = lifecycleOwner,
        onEvent = {
            viewModel.dispatchHostPause(permissionHostId)
        },
    )
    DisposableEffect(viewModel, permissionHostId, lifecycleOwner, view) {
        val lifecycle = lifecycleOwner.lifecycle
        val refreshObserver = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            viewModel.dispatchHostWindowFocus(
                hostId = permissionHostId,
                hasWindowFocus = hasFocus,
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
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

    Box(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                Column {
                    AnimatedVisibility(
                        visible = privilegeUiAutoRecoveryWarningVisible(
                            state = state,
                            interactionEnabled = interactionEnabled,
                        ),
                    ) {
                        Column {
                            screenScope.AutoRecoveryWarning()
                            Spacer(Modifier.height(PrivilegeUiSpacing.large))
                        }
                    }
                    screenScope.ServiceStatusPanel()
                }
                screenScope.PermissionRestrictionWarning()
                screenScope.AuthorizationModeTabs()
                screenScope.AuthorizationModePanel()
                if (state.startupLogLines.isNotEmpty()) {
                    screenScope.StartupLogPanel()
                }
            }
        }
        PrivilegeSystemPromptOverlay(
            prompt = visibleSystemPrompt,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                )
                .padding(
                    horizontal = PrivilegeUiSpacing.large,
                    vertical = PrivilegeUiSpacing.medium,
                ),
        )
    }
}

internal fun privilegeUiAutoRecoveryWarningVisible(
    state: PrivilegeUiState,
    interactionEnabled: Boolean,
): Boolean = interactionEnabled &&
    privilegeUiAutoRecoveryWarningVisible(
        desiredEnabled = state.desiredEnabled,
        runtimeStatus = state.runtimeStatus,
        runtimeStartPhase = state.runtimeStartPhase,
    )

internal class PrivilegeUiScreenScope(
    val state: PrivilegeUiState,
    val viewModel: PrivilegeUiViewModel,
    val interactionEnabled: Boolean,
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
