package priv.kit.ui

import android.Manifest
import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
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
import priv.kit.ui.component.PrivilegeSystemPromptOverlay
import priv.kit.ui.component.PrivilegeTopBar
import priv.kit.ui.component.PrivilegeUiSpacing
import java.util.UUID

@Composable
public fun PrivilegeScaffold(
    modifier: Modifier = Modifier,
    viewModel: PrivilegeUiViewModel = viewModel(),
    topBar: @Composable () -> Unit = {
        PrivilegeAndroidTopBar(viewModel)
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
    val visibleSystemPrompt by remember(viewModel.visibleSystemPrompt) {
        viewModel.visibleSystemPrompt.debouncedForDisplay()
    }.collectAsStateWithLifecycle(initialValue = null)
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
    val batteryOptimizationPromptVisible by viewModel.batteryOptimizationPromptVisible.collectAsStateWithLifecycle()
    val staticTcpSwitchConfirmation by viewModel.staticTcpSwitchConfirmation.collectAsStateWithLifecycle()
    // Reading LocalConfiguration makes retained text follow application locale changes.
    androidx.compose.ui.platform.LocalConfiguration.current
    val screenScope = PrivilegeUiScreenScope(
        state = state.toScreenState { it.asString(context) }.copy(
            wirelessAdbSupported = priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported(),
            adbTcpPolicy = viewModel.config.adbTcpPolicy,
            tcpPort = viewModel.config.tcpPort,
            batteryOptimizationPromptVisible = batteryOptimizationPromptVisible,
            staticTcpSwitchConfirmation = staticTcpSwitchConfirmation,
        ),
        actions = remember(viewModel, context) { viewModel.screenActions(context) },
        interactionEnabled = interactionEnabled,
        showFeedback = ::showFeedback,
    )
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
                                    runCatching {
                                        notificationPermissionLauncher.launch(notificationPermission)
                                    }.onSuccess {
                                        markPrivilegeUiPermissionRequested(notificationPermission)
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
                        runCatching {
                            localNetworkPermissionLauncher.launch(request.permission)
                        }.onSuccess {
                            markPrivilegeUiPermissionRequested(request.permission)
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
        PrivilegeScaffoldContent(
            screenScope = screenScope,
            modifier = Modifier.fillMaxSize(),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            snackbarHostState = snackbarHostState,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
        )
        PrivilegeSystemPromptOverlay(
            prompt = visibleSystemPrompt
                ?.takeIf { it.ownerHostId == permissionHostId }
                ?.prompt,
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

@Composable
private fun PrivilegeAndroidTopBar(viewModel: PrivilegeUiViewModel) {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    PrivilegeTopBar(onBack = {
        if (!viewModel.dispatchBackClick()) dispatcher?.onBackPressed()
    })
}
