package priv.kit.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.PrivilegeServerInfo

@Composable
public fun PrivilegeScaffold(
    modifier: Modifier = Modifier,
    viewModel: PrivilegeUiViewModel = viewModel(),
    onBackClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onConnected: (PrivilegeServerInfo) -> Unit = {},
    onNotificationPermissionRequired: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val tcpModeEnabled by viewModel.tcpModeEnabled.collectAsState()
    LaunchedEffect(state.connectionSerial) {
        val serverInfo = state.serverInfo
        if (state.connectionSerial > 0L && serverInfo != null) {
            onConnected(serverInfo)
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

    MaterialTheme {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            topBar = {
                PrivilegeTopBar(
                    onBackClick = onBackClick,
                    onHelpClick = onHelpClick,
                )
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
                ServiceStatusPanel(state)
                AuthorizationModeTabs(
                    modes = state.startupModes,
                    selectedMode = state.selectedStartupMode,
                    enabled = !state.busy,
                    onSelected = viewModel::selectStartupMode,
                )
                AuthorizationModePanel(
                    mode = state.selectedStartupMode.takeIf { it in state.startupModes }
                        ?: state.startupModes.first(),
                    state = state,
                    tcpModeEnabled = tcpModeEnabled,
                    viewModel = viewModel,
                    onCopyManualCommand = { viewModel.copyManualCommand(context) },
                    onNotificationPermissionRequired = onNotificationPermissionRequired,
                )
                if (state.startupLogLines.isNotEmpty()) {
                    StartupLogPanel(state.startupLogLines)
                }
            }
        }
    }
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.findLifecycleOwner()
        else -> null
    }

@Composable
private fun AuthorizationModePanel(
    mode: PrivilegeUiStartupMode,
    state: PrivilegeUiState,
    tcpModeEnabled: Boolean,
    viewModel: PrivilegeUiViewModel,
    onCopyManualCommand: () -> Unit,
    onNotificationPermissionRequired: () -> Unit,
) {
    when (mode) {
        PrivilegeUiStartupMode.ROOT -> RootPanel(
            state = state,
            onStartRoot = viewModel::startRoot,
        )
        PrivilegeUiStartupMode.MANUAL_SHELL -> ManualShellPanel(
            state = state,
            onCopyCommand = onCopyManualCommand,
        )
        PrivilegeUiStartupMode.ADB -> AdbPanel(
            state = state,
            tcpModeEnabled = tcpModeEnabled,
            tcpPolicy = viewModel.adbTcpPolicy,
            onPairingCodeChanged = viewModel::updatePairingCode,
            onPairByCode = viewModel::pairWirelessAdb,
            onNotificationPairingClick = {
                viewModel.toggleNotificationPairing(onNotificationPermissionRequired)
            },
            onEnableTcpMode = viewModel::enableTcpMode,
            onStartAdb = viewModel::startAdb,
        )
        PrivilegeUiStartupMode.EXTERNAL -> ExternalStartPanel(
            state = state,
            onAuthorizeOrStart = viewModel::authorizeOrStartExternal,
        )
    }
}
