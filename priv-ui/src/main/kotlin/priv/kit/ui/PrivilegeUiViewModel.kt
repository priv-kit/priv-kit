package priv.kit.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiDirectStartTarget
import priv.kit.ui.runtime.PrivilegeUiDesiredEnabledManagers
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartGateState
import priv.kit.ui.runtime.copyManualShellCommand
import priv.kit.ui.runtime.directStartTargets
import priv.kit.ui.state.PrivilegeUiNoopCloseable
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.copyToClipboard
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand
import priv.kit.ui.state.toPrivilegeUiDiagnosticString
import java.util.concurrent.atomic.AtomicBoolean

public open class PrivilegeUiViewModel @JvmOverloads public constructor(
    application: Application,
    public val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) : AndroidViewModel(application) {
    private val store = PrivilegeUiViewModelStore(application)
    private val desiredEnabledManager = PrivilegeUiDesiredEnabledManagers.get(application)
    private val interactiveStartOwner = PrivilegeUiStartGate.newInteractiveOwner()
    private val acquireInteractivePermit = interactiveStartOwner::tryAcquire
    private val runtimeActions = PrivilegeUiRuntimeActions(
        store = store,
        coroutineScope = viewModelScope,
        acquireStartPermit = acquireInteractivePermit,
    )
    private val adbActions = PrivilegeUiAdbActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
        acquireInteractivePermit = acquireInteractivePermit,
        hasInteractionHost = ::hasPermissionInteractionHost,
    )
    private val externalStartActions = PrivilegeUiExternalStartActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
        acquireInteractivePermit = acquireInteractivePermit,
    )
    private val ownerClosed = AtomicBoolean(false)
    private val effectsCoordinator = PrivilegeUiEffectsCoordinator(
        store = store,
        interactiveStartOwner = interactiveStartOwner,
        runtimeActions = runtimeActions,
        adbActions = adbActions,
        externalStartActions = externalStartActions,
        coroutineScope = viewModelScope,
    )
    private val permissionCoordinator = PrivilegeUiPermissionCoordinator(
        coroutineScope = viewModelScope,
        acquireInteractivePermit = acquireInteractivePermit,
        interactionsEnabled = { uiInteractionsEnabled },
        ownerClosed = ownerClosed::get,
        handleNotificationPermissionResult = adbActions::handleNotificationPermissionResult,
        cancelNotificationPermissionRequest = adbActions::cancelNotificationPermissionRequest,
        cancelPairingWithoutInteractionHost = adbActions::cancelPairingWithoutInteractionHost,
    )
    private var batteryOptimizationRefreshJob: Job? = null
    private var deliveredConnectionSerial = 0L
    private var hostResumeDispatchInProgress = false
    private val batteryOptimizationPromptVisibleState = MutableStateFlow(false)
    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    internal val startGateState: StateFlow<PrivilegeUiStartGateState> =
        effectsCoordinator.startGateState
    internal val uiEffectsEnabled: StateFlow<Boolean> = effectsCoordinator.enabled
    internal val uiInteractionsEnabled: Boolean
        get() = effectsCoordinator.interactionsEnabled
    internal val snackbarMessages: SharedFlow<String> = store.snackbarMessages
    internal val permissionRequests: Flow<PrivilegeUiPermissionRequest> = permissionCoordinator.requests
    internal val batteryOptimizationPromptVisible: StateFlow<Boolean> =
        batteryOptimizationPromptVisibleState.asStateFlow()

    init {
        addCloseable { closeOwner() }
        configure(config)
    }

    private fun configure(config: PrivilegeUiConfig) {
        store.config = config

        adbActions.observePairingNotificationEvents()
        store.initializeState(config)
        store.updateState {
            it.copy(desiredEnabled = desiredEnabledManager.desiredEnabled.value)
        }
        viewModelScope.launch {
            desiredEnabledManager.desiredEnabled.collect { enabled ->
                store.updateState { it.copy(desiredEnabled = enabled) }
            }
        }
        runtimeActions.installRuntimeWatchers()
        effectsCoordinator.initialize()

        refreshBatteryOptimizationState()
    }

    internal fun uiEffectsAllowed(gateState: PrivilegeUiStartGateState): Boolean =
        effectsCoordinator.effectsAllowed(gateState)

    /** Return true when the host handled the back action; false uses the system back dispatcher. */
    protected open fun onBackClick(): Boolean = false

    /** Called once for each connection serial while this ViewModel is alive. */
    protected open fun onConnected(serverInfo: PrivilegeServerInfo): Unit = Unit

    /**
     * Called when the built-in UI requests the host application's notification settings.
     * Implementations must not retain [context].
     */
    protected open fun onNotificationPermissionSettingsRequested(context: Context) {
        context.tryStartPrivilegeUiSettingsActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                Settings.EXTRA_APP_PACKAGE,
                context.packageName,
            ),
        )
    }

    internal fun dispatchBackClick(): Boolean = onBackClick()

    internal fun dispatchNotificationPermissionSettingsRequest(context: Context) {
        if (!uiInteractionsEnabled) return
        onNotificationPermissionSettingsRequested(context)
    }

    internal fun dispatchConnected(
        connectionSerial: Long,
        serverInfo: PrivilegeServerInfo,
    ) {
        if (connectionSerial <= deliveredConnectionSerial) return
        deliveredConnectionSerial = connectionSerial
        onConnected(serverInfo)
    }

    public open fun updatePairingCode(value: String) {
        if (!uiInteractionsEnabled) return
        adbActions.updatePairingCode(value)
    }

    public open fun selectStartupMode(mode: PrivilegeUiStartupMode) {
        if (!uiInteractionsEnabled) return
        if (mode !in store.state.value.startupModes) return
        store.updateState { it.copy(selectedStartupMode = mode) }
        effectsCoordinator.onStartupModeSelected()
    }

    public open fun startRoot() {
        if (!uiInteractionsEnabled) return
        runtimeActions.startRoot()
    }

    /** Starts the foreground provider sequence used by the service-status action. */
    public open fun startInteractive() {
        if (!uiInteractionsEnabled) return
        startInteractiveFallback()
    }

    private fun startInteractiveFallback() {
        if (
            store.state.value.busy ||
            store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
        ) {
            return
        }
        adbActions.refreshAdbStartPrerequisites()
        val directTargets = store.state.value.directStartTargets(
            tcpPolicy = store.config.adbTcpPolicy,
            wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
        )
        val attempts = directTargets.flatMap { target ->
            when (target) {
                PrivilegeUiDirectStartTarget.Adb -> adbActions.directStartAttempts()
                is PrivilegeUiDirectStartTarget.External -> {
                    listOfNotNull(externalStartActions.directStartAttempt(target.providerId))
                }
                PrivilegeUiDirectStartTarget.Root -> listOf(runtimeActions.rootStartAttempt())
            }
        }
        runtimeActions.runServerStartFallback(attempts)
    }

    public open fun stopServer() {
        if (!uiInteractionsEnabled) return
        runtimeActions.stopServer(beforeShutdown = ::disableDesiredEnabled)
    }

    /** Disables future automatic recovery without requiring a connected server. */
    public open fun disableAutoRecovery() {
        if (!uiInteractionsEnabled) return
        disableDesiredEnabled()
    }

    public open fun stopCurrentStart() {
        if (!uiInteractionsEnabled) return
        runtimeActions.stopCurrentStart()
    }

    public open fun copyManualCommand(context: Context) {
        if (!uiInteractionsEnabled) return
        store.copyManualShellCommand(context)
    }

    public open fun copyStaticTcpCommand(context: Context) {
        if (!uiInteractionsEnabled) return
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_adb_static_command_clip_label),
            text = privilegeUiStaticTcpOpenCommand(store.config.tcpPort),
        )
    }

    public open fun copyStartupLog(context: Context) {
        if (!uiInteractionsEnabled) return
        val logText = store.state.value.startupLogLines.joinToString("\n")
        if (logText.isBlank()) return
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_startup_log_clip_label),
            text = logText,
        )
    }

    public open fun startNotificationPairing() {
        if (!uiInteractionsEnabled) return
        adbActions.startNotificationPairing(
            onNotificationPermissionRequired = ::requestNotificationPermission,
        )
    }

    public open fun stopNotificationPairing() {
        if (!uiInteractionsEnabled) return
        adbActions.stopNotificationPairing()
    }

    public open fun cancelPendingPairingStart() {
        if (!uiInteractionsEnabled) return
        adbActions.cancelPendingPairingStart()
    }

    public open fun continuePairingWithoutNotification() {
        if (!uiInteractionsEnabled) return
        adbActions.continuePairingWithoutNotification()
    }

    public open fun closePairingDialog() {
        if (!uiInteractionsEnabled) return
        adbActions.closePairingDialog()
    }

    public open fun submitNotificationPairingCode() {
        if (!uiInteractionsEnabled) return
        adbActions.submitNotificationPairingCode()
    }

    internal fun completeNotificationPermissionRequest(
        hostId: String,
        permissionState: PrivilegeUiPermissionState,
    ) = permissionCoordinator.completeNotificationPermissionRequest(hostId, permissionState)

    internal fun completeUnlaunchedNotificationPermissionRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest.Notification,
        permissionState: PrivilegeUiPermissionState,
    ) = permissionCoordinator.completeUnlaunchedNotificationPermissionRequest(
        hostId,
        request,
        permissionState,
    )

    internal fun completeLocalNetworkPermissionRequest(hostId: String) =
        permissionCoordinator.completeLocalNetworkPermissionRequest(hostId)

    public open fun startWirelessAdb() {
        if (!uiInteractionsEnabled) return
        adbActions.startWirelessAdb(::requestLocalNetworkPermission)
    }

    public open fun startAdb() {
        if (!uiInteractionsEnabled) return
        adbActions.startAdb(::requestLocalNetworkPermission)
    }

    public open fun startWirelessAdbStatusPolling(): AutoCloseable =
        if (!uiInteractionsEnabled) {
            PrivilegeUiNoopCloseable
        } else if (isPrivilegeUiWirelessAdbSupported()) {
            adbActions.startWirelessAdbStatusPolling()
        } else {
            adbActions.stopWirelessAdbStatusPolling()
            PrivilegeUiNoopCloseable
        }

    public open fun refreshWirelessAdbStatus() {
        if (uiInteractionsEnabled && isPrivilegeUiWirelessAdbSupported()) {
            adbActions.refreshWirelessAdbStatus()
        }
    }

    public open fun onHostResume() {
        if (!hostResumeDispatchInProgress) {
            refreshHostInteractiveState()
        }
    }

    private fun refreshHostInteractiveState() {
        refreshBatteryOptimizationState()
        if (uiInteractionsEnabled) {
            adbActions.continuePendingPairingIfNotificationPermissionGranted()
            effectsCoordinator.refreshHostResumeState()
        } else {
            effectsCoordinator.pauseStatusPolling()
        }
        scheduleBatteryOptimizationStateRechecks()
    }

    internal fun dispatchHostResume() {
        refreshHostInteractiveState()
        hostResumeDispatchInProgress = true
        try {
            onHostResume()
        } finally {
            hostResumeDispatchInProgress = false
        }
    }

    internal fun dispatchHostWindowFocus() {
        refreshHostInteractiveState()
    }

    private fun refreshBatteryOptimizationState() {
        batteryOptimizationPromptVisibleState.value = store.requireContext()
            .isPrivilegeUiBatteryOptimizationPromptVisible()
    }

    private fun scheduleBatteryOptimizationStateRechecks() {
        batteryOptimizationRefreshJob?.cancel()
        batteryOptimizationRefreshJob = viewModelScope.launch {
            for (delayMillis in BATTERY_OPTIMIZATION_RECHECK_DELAYS_MILLIS) {
                delay(delayMillis)
                refreshBatteryOptimizationState()
            }
        }
    }

    public open fun stopWirelessAdbStatusPolling() {
        effectsCoordinator.stopWirelessStatusPolling()
    }

    public open fun startTcpModeStatusPolling(): AutoCloseable =
        if (uiInteractionsEnabled) {
            adbActions.startTcpModeStatusPolling()
        } else {
            PrivilegeUiNoopCloseable
        }

    public open fun stopTcpModeStatusPolling() {
        effectsCoordinator.stopTcpModeStatusPolling()
    }

    public open fun enableTcpMode() {
        if (!uiInteractionsEnabled) return
        adbActions.enableTcpMode()
    }

    public open fun refreshTcpModeEnabled() {
        if (!uiInteractionsEnabled) return
        adbActions.refreshTcpModeEnabled()
    }

    public open fun startStaticTcpAdb() {
        if (!uiInteractionsEnabled) return
        adbActions.startStaticTcpAdb(::requestLocalNetworkPermission)
    }

    public open fun refreshExternalStartStatus(providerId: String? = null) {
        if (!uiInteractionsEnabled) return
        externalStartActions.refreshExternalStartStatus(providerId)
    }

    public open fun startExternalStartStatusPolling(): AutoCloseable =
        if (uiInteractionsEnabled) {
            externalStartActions.startExternalStartStatusPolling()
        } else {
            PrivilegeUiNoopCloseable
        }

    public open fun stopExternalStartStatusPolling() {
        effectsCoordinator.stopExternalStartStatusPolling()
    }

    public open fun authorizeOrStartExternal(providerId: String) {
        if (!uiInteractionsEnabled) return
        externalStartActions.authorizeOrStartExternal(providerId)
    }

    private fun requestNotificationPermission(): Boolean {
        return permissionCoordinator.requestNotificationPermission()
    }

    private fun hasPermissionInteractionHost(): Boolean =
        permissionCoordinator.hasInteractionHost()

    private fun requestLocalNetworkPermission(permission: String) {
        permissionCoordinator.requestLocalNetworkPermission(permission)
    }

    internal fun registerPermissionHost(hostId: String) = permissionCoordinator.registerHost(hostId)

    internal fun unregisterPermissionHost(
        hostId: String,
        changingConfigurations: Boolean,
    ) = permissionCoordinator.unregisterHost(hostId, changingConfigurations)

    internal fun completePermissionRequest(request: PrivilegeUiPermissionRequest) =
        permissionCoordinator.completeRequest(request)

    internal fun cancelPermissionRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest,
    ) = permissionCoordinator.cancelRequest(hostId, request)

    override fun onCleared() {
        closeOwner()
    }

    private fun closeOwner() {
        if (!ownerClosed.compareAndSet(false, true)) return
        batteryOptimizationRefreshJob?.cancel()
        batteryOptimizationRefreshJob = null
        runCatching { effectsCoordinator.close() }
        runtimeActions.close()
        runCatching { externalStartActions.close() }
        runCatching { adbActions.close() }
        permissionCoordinator.close()
        runCatching { store.close() }
    }

    private fun disableDesiredEnabled() {
        runCatching {
            desiredEnabledManager.setDesiredEnabled(false)
        }.onFailure { throwable ->
            store.showSnackbar(store.text(R.string.priv_ui_auto_recovery_disable_failed))
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }
}

private val BATTERY_OPTIMIZATION_RECHECK_DELAYS_MILLIS = listOf(250L, 750L, 1_500L)
