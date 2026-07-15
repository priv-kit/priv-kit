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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import priv.kit.PrivilegeServerInfo
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
    onBackClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    onConnected: (PrivilegeServerInfo) -> Unit = {},
    onNotificationPermissionRequired: () -> Unit = {},
    onLocalNetworkPermissionRequired: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val manualCommandCopiedMessage = stringResource(R.string.priv_ui_manual_command_copied)
    val staticTcpCommandCopiedMessage = stringResource(R.string.priv_ui_adb_static_command_copied)
    val startupLogCopiedMessage = stringResource(R.string.priv_ui_startup_log_copied)
    fun showFeedback(message: String) {
        snackbarScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }
    LaunchedEffect(state.connectionSerial) {
        val serverInfo = state.serverInfo
        if (state.connectionSerial > 0L && serverInfo != null) {
            onConnected(serverInfo)
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
            PrivilegeTopBar(
                onBackClick = onBackClick,
                onHelpClick = onHelpClick,
            )
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
            ServiceStatusPanel(
                state = state,
                onStartClick = {
                    viewModel.startAvailable(onLocalNetworkPermissionRequired)
                },
                onStopClick = viewModel::stopServer,
            )
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
                developerModeEnabled = developerModeEnabled,
                viewModel = viewModel,
                onCopyManualCommand = {
                    viewModel.copyManualCommand(context)
                    showFeedback(manualCommandCopiedMessage)
                },
                onCopyStaticTcpCommand = {
                    viewModel.copyStaticTcpCommand(context)
                    showFeedback(staticTcpCommandCopiedMessage)
                },
                onNotificationPermissionRequired = onNotificationPermissionRequired,
                onLocalNetworkPermissionRequired = onLocalNetworkPermissionRequired,
            )
            if (state.startupLogLines.isNotEmpty()) {
                StartupLogPanel(
                    lines = state.startupLogLines,
                    onCopyLog = {
                        viewModel.copyStartupLog(context)
                        showFeedback(startupLogCopiedMessage)
                    },
                )
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
    developerModeEnabled: Boolean?,
    viewModel: PrivilegeUiViewModel,
    onCopyManualCommand: () -> Unit,
    onCopyStaticTcpCommand: () -> Unit,
    onNotificationPermissionRequired: () -> Unit,
    onLocalNetworkPermissionRequired: (String) -> Unit,
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
            developerModeEnabled = developerModeEnabled,
            tcpPolicy = viewModel.adbTcpPolicy,
            managedWirelessAdbEnabled = viewModel.config.enableManagedWirelessAdb,
            configuredTcpPort = viewModel.config.tcpPort,
            onPairingCodeChanged = viewModel::updatePairingCode,
            onStartPairing = {
                viewModel.startNotificationPairing(
                    onNotificationPermissionRequired = onNotificationPermissionRequired,
                )
            },
            onStopPairing = viewModel::stopNotificationPairing,
            onClosePairing = viewModel::closePairingDialog,
            onSubmitPairingCode = viewModel::submitNotificationPairingCode,
            onEnableTcpMode = viewModel::enableTcpMode,
            onStartWirelessAdb = {
                viewModel.startWirelessAdb(onLocalNetworkPermissionRequired)
            },
            onStopWirelessAdb = viewModel::stopCurrentStart,
            onStartStaticTcpAdb = {
                viewModel.startStaticTcpAdb(onLocalNetworkPermissionRequired)
            },
            onCopyStaticTcpCommand = onCopyStaticTcpCommand,
        )
        PrivilegeUiStartupMode.EXTERNAL -> ExternalStartPanel(
            state = state,
            onAuthorizeOrStart = viewModel::authorizeOrStartExternal,
        )
    }
}
