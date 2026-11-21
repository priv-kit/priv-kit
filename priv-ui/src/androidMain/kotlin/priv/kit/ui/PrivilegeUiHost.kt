package priv.kit.ui

import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Bind once per page. Owns platform effects and draws no UI. */
@Composable
@ExperimentalPrivilegeUiApi
public fun rememberPrivilegeUi(
    onMessage: (String) -> Unit,
    viewModel: PrivilegeUiViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
): PrivilegeUiRenderScope {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startGateState by viewModel.startGateState.collectAsStateWithLifecycle()
    val authorization by viewModel.authorization.collectAsStateWithLifecycle()
    val interactionEnabled = viewModel.canInteract(startGateState)
    DisposableEffect(viewModel, activity, lifecycleOwner, view) {
        val accessHost = PrivilegeUiAccessHost(activity, viewModel.accessCommands)
        viewModel.registerPermissionHost(
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        onDispose {
            accessHost.close()
            viewModel.unregisterPermissionHost(
                changingConfigurations = activity.isChangingConfigurations,
            )
        }
    }
    val batteryOptimizationRestricted by viewModel.batteryOptimizationRestricted.collectAsStateWithLifecycle()
    val staticTcpSwitchConfirmation by viewModel.staticTcpSwitchConfirmation.collectAsStateWithLifecycle()
    // Reading LocalConfiguration makes retained text follow application locale changes.
    val configuration = LocalConfiguration.current
    LaunchedEffect(viewModel, context, configuration) {
        viewModel.refreshExternalStartLabels(context)
    }
    val screenState = state.toScreenState { it.asString(context) }.copy(
        wirelessAdbSupported = priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported(),
        adbTcpPolicy = viewModel.config.adbTcpPolicy,
        tcpPort = viewModel.config.tcpPort,
        batteryOptimizationRestricted = batteryOptimizationRestricted,
        staticTcpSwitchConfirmation = staticTcpSwitchConfirmation,
    )
    val currentOnMessage by rememberUpdatedState(onMessage)
    val currentOnBack by rememberUpdatedState(onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val actions = remember(viewModel) { viewModel.screenActions() }
    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { currentOnMessage(it.asString(context)) }
    }
    LifecycleEventEffect(
        event = Lifecycle.Event.ON_RESUME,
        lifecycleOwner = lifecycleOwner,
        onEvent = {
            viewModel.dispatchHostResume()
        },
    )
    LifecycleEventEffect(
        event = Lifecycle.Event.ON_PAUSE,
        lifecycleOwner = lifecycleOwner,
        onEvent = {
            viewModel.dispatchHostPause()
        },
    )
    DisposableEffect(viewModel, activity, lifecycleOwner, view) {
        val lifecycle = lifecycleOwner.lifecycle
        val refreshObserver = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            viewModel.dispatchHostWindowFocus(
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

    return PrivilegeUiRenderScope(
        state = screenState,
        actions = actions,
        interactionEnabled = interactionEnabled,
        authorization = authorization,
        onBack = { currentOnBack?.invoke() ?: dispatcher?.onBackPressed() },
        showMessage = { currentOnMessage(it) },
    )
}

/** Calls [content] with the same setup as [rememberPrivilegeUi], without a layout or theme. */
@Composable
@ExperimentalPrivilegeUiApi
public fun PrivilegeUiHost(
    onMessage: (String) -> Unit,
    viewModel: PrivilegeUiViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
    content: @Composable (PrivilegeUiRenderScope) -> Unit,
) {
    content(rememberPrivilegeUi(onMessage, viewModel, onBack))
}
