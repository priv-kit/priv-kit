package priv.kit.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import priv.kit.core.Privilege
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchAction
import priv.kit.ui.adb.privilegeUiRequiredLocalNetworkPermission
import priv.kit.ui.adb.refreshLocalNetworkPermission
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiDirectStartTarget
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartGateState
import priv.kit.ui.runtime.copyManualShellCommand
import priv.kit.ui.runtime.directStartTargets
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.copyToClipboard
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand
import priv.kit.ui.state.toPrivilegeUiDiagnosticString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

@ExperimentalPrivilegeUiApi
public open class PrivilegeUiViewModel @JvmOverloads public constructor(
    application: Application,
    public val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) : AndroidViewModel(application) {
    private val store = PrivilegeUiViewModelStore(application, config).also { createdStore ->
        if (PrivilegeUiStartupMode.ADB in createdStore.state.value.startupModes) {
            runCatching {
                Privilege.createAdbManager().getIdentityInfo()
            }.onSuccess { info ->
                createdStore.updateState {
                    it.copy(adbKeyFingerprint = info.publicKeyFingerprint)
                }
            }.onFailure { throwable ->
                createdStore.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
            }
        }
    }
    private val interactiveStartOwner = PrivilegeUiStartGate.newInteractiveOwner()
    private val acquireInteractivePermit = interactiveStartOwner::tryAcquire
    private val authorizationCoordinator = PrivilegeUiAuthorizationCoordinator()
    private val runtimeActions = PrivilegeUiRuntimeActions(
        store = store,
        coroutineScope = viewModelScope,
        acquireStartPermit = acquireInteractivePermit,
        authorizationCoordinator = authorizationCoordinator,
    )
    private val adbActions = PrivilegeUiAdbActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
        acquireInteractivePermit = acquireInteractivePermit,
        hasInteractionHost = ::hasPermissionInteractionHost,
        authorizationCoordinator = authorizationCoordinator,
    )
    private val externalStartActions = PrivilegeUiExternalStartActions(
        store = store,
        runtimeActions = runtimeActions,
        acquireInteractivePermit = acquireInteractivePermit,
        authorizationCoordinator = authorizationCoordinator,
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
        acquireInteractivePermit = acquireInteractivePermit,
        interactionsEnabled = { uiInteractionsEnabled },
        ownerClosed = ownerClosed::get,
        cancelPairingWithoutInteractionHost = adbActions::cancelPairingWithoutInteractionHost,
        handler = { request ->
            try {
                accessHandler.request(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.showSnackbar(PrivilegeUiText.Literal(error.message ?: error.toString()))
                throw kotlinx.coroutines.CancellationException("Access request failed", error)
            } finally { refreshPermissions() }
        },
    )
    private var localNetworkPermissionJob: Job? = null
    private var notificationPairingStartJob: Job? = null
    private var externalAuthorizationJob: Job? = null
    private var batteryOptimizationRefreshJob: Job? = null
    private val serverRestartDecisionLock = Any()
    private var pendingServerRestartDecision: PendingServerRestartDecision? = null
    private var hostResumeDispatchInProgress = false
    private val batteryOptimizationRestrictedState = MutableStateFlow(false)
    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    public val startGateState: StateFlow<PrivilegeUiStartGateState> =
        effectsCoordinator.startGateState
    public val uiInteractionsEnabled: Boolean
        get() = effectsCoordinator.canInteract()
    public val messages: SharedFlow<PrivilegeUiText> = store.snackbarTexts
    internal val accessCommands = PrivilegeUiAccessCommands()
    /** Stable, VM-owned handler. Overrides must not capture an Activity or a composition scope. */
    protected open val accessHandler: PrivilegeUiAccessHandler = PrivilegeUiAccessHandler(::requestPlatformAccess)
    private var batteryOptimizationRequestJob: Job? = null
    public val authorization: StateFlow<PrivilegeUiAuthorization?> =
        authorizationCoordinator.authorization
    public val batteryOptimizationRestricted: StateFlow<Boolean> =
        batteryOptimizationRestrictedState.asStateFlow()
    /**
     * The pending foreground action that requires confirmation before restarting ADB in TCP mode.
     * Custom UI surfaces should explain the restart impact, then call [confirmStaticTcpSwitch] or
     * [cancelStaticTcpSwitch]. A non-null value is only an unapproved request and has no ADB side
     * effects by itself.
     */
    public val staticTcpSwitchConfirmation: StateFlow<PrivilegeUiStaticTcpSwitchAction?> =
        adbActions.staticTcpSwitchConfirmation
    /**
     * A pending request to replace the currently connected Privileged Server.
     *
     * The built-in M3 page presents this request as a confirmation dialog. Custom
     * surfaces should collect it and call [confirmServerRestart] or [cancelServerRestart].
     * Merely observing the request has no startup or shutdown side effects.
     */
    public val serverRestartConfirmation: StateFlow<PrivilegeUiServerRestartRequest?> =
        store.state
            .map { current -> current.restartConfirmationTarget }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = store.state.value.restartConfirmationTarget,
            )

    init {
        addCloseable { closeOwner() }
        configure()
    }

    private fun configure() {
        store.refreshLocalNetworkPermission()
        runtimeActions.initializeRuntimeState()
        adbActions.observeLocalNetworkPermission(effectsCoordinator::refreshHostResumeState)
        adbActions.observePairingNotificationEvents()
        store.updateState {
            it.copy(desiredEnabled = PrivilegeUi.desiredEnabled.value)
        }
        viewModelScope.launch {
            PrivilegeUi.desiredEnabled.collect { enabled ->
                store.updateState { it.copy(desiredEnabled = enabled) }
            }
        }
        runtimeActions.installRuntimeWatchers()
        effectsCoordinator.initialize()

        refreshBatteryOptimizationState()
    }

    public fun canInteract(gateState: PrivilegeUiStartGateState): Boolean =
        effectsCoordinator.canInteract(gateState)

    public open fun updatePairingCode(value: String) {
        if (!uiInteractionsEnabled) return
        adbActions.updatePairingCode(value)
    }

    public open fun startRoot() {
        requestServerStart(PrivilegeUiServerRestartRequest.Root)
    }

    /** Starts the foreground provider sequence used by the service-status action. */
    public open fun startInteractive() {
        if (!uiInteractionsEnabled) return
        viewModelScope.launch { startInteractiveFallback() }
    }

    private suspend fun startInteractiveFallback() {
        if (
            store.state.value.busy ||
            store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
        ) {
            return
        }
        if (PrivilegeUiStartupMode.ADB in store.state.value.startupModes) {
            adbActions.refreshAdbStartPrerequisites()
        }
        externalStartActions.refreshExternalStartStatusNow(providerId = null)
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

    public open fun copyManualCommand() {
        if (!uiInteractionsEnabled) return
        store.copyManualShellCommand()
    }

    public open fun copyStaticTcpCommand() {
        if (!uiInteractionsEnabled) return
        getApplication<Application>().copyToClipboard(
            label = store.text(R.string.priv_ui_adb_static_command_clip_label),
            text = privilegeUiStaticTcpOpenCommand(store.config.tcpPort),
        )
    }

    public open fun copyStartupLog() {
        if (!uiInteractionsEnabled) return
        val logText = store.state.value.startupLogLines.joinToString("\n")
        if (logText.isBlank()) return
        getApplication<Application>().copyToClipboard(
            label = store.text(R.string.priv_ui_startup_log_clip_label),
            text = logText,
        )
    }

    public open fun clearStartupLog() {
        if (!uiInteractionsEnabled) return
        store.clearStartupLog()
    }

    public open fun startNotificationPairing() {
        if (!uiInteractionsEnabled) return
        if (notificationPairingStartJob?.isActive == true) return
        notificationPairingStartJob = viewModelScope.launch {
            adbActions.startNotificationPairing(permissionCoordinator::requestNotificationPermission)
        }
    }

    public open fun cancelPairing() {
        if (!uiInteractionsEnabled) return
        adbActions.cancelPairing()
    }

    public open fun cancelPendingPairingStart() {
        if (!uiInteractionsEnabled) return
        if (store.state.value.pairingInteraction != PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION) {
            notificationPairingStartJob?.cancel()
        }
        notificationPairingStartJob = null
        adbActions.cancelPendingPairingStart()
    }

    public open fun continuePairingWithoutNotification() {
        if (!uiInteractionsEnabled) return
        adbActions.continuePairingWithoutNotification()
    }


    public open fun submitNotificationPairingCode() {
        if (!uiInteractionsEnabled) return
        adbActions.submitNotificationPairingCode()
    }

    public fun requestAdbLocalNetworkPermission() {
        if (!uiInteractionsEnabled || localNetworkPermissionJob?.isActive == true) return
        if (store.refreshLocalNetworkPermission()) return
        localNetworkPermissionJob = viewModelScope.launch {
            if (privilegeUiRequiredLocalNetworkPermission(store.requireContext()) == null) return@launch
            try {
                permissionCoordinator.requestLocalNetworkPermission()
            } finally {
                store.refreshLocalNetworkPermission()
            }
        }
    }

    public fun requestBatteryOptimization() {
        if (!uiInteractionsEnabled || batteryOptimizationRequestJob?.isActive == true) return
        batteryOptimizationRequestJob = viewModelScope.launch {
            permissionCoordinator.request(PrivilegeUiAccessRequest.BatteryOptimization)
        }
    }

    /** Android operations only; presentation subclasses may add guidance around them. */
    protected suspend fun requestPlatformAccess(request: PrivilegeUiAccessRequest): Boolean = when (request) {
        is PrivilegeUiAccessRequest.Permission ->
            accessCommands.permission(request.permission) == PrivilegeUiPermissionState.Granted
        PrivilegeUiAccessRequest.BatteryOptimization -> {
            val application = getApplication<Application>()
            if (isPrivilegeUiBatteryOptimizationRestricted(application)) {
                check(openAccessSettings(PrivilegeUiSettingsDestination.BATTERY_OPTIMIZATION)) {
                    application.getString(R.string.priv_ui_battery_optimization_settings_unavailable)
                }
            }
            !isPrivilegeUiBatteryOptimizationRestricted(application)
        }
    }

    protected suspend fun requiresAccessSettings(request: PrivilegeUiAccessRequest.Permission): Boolean =
        accessCommands.permission(request.permission, checkOnly = true) ==
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied

    protected fun isAccessGranted(request: PrivilegeUiAccessRequest.Permission): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), request.permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    protected suspend fun openAccessSettings(destination: PrivilegeUiSettingsDestination): Boolean =
        accessCommands.openSettings(destination)

    public fun refreshPermissions() {
        store.refreshLocalNetworkPermission()
        refreshBatteryOptimizationState()
    }

    public open fun startWirelessAdb() {
        requestServerStart(PrivilegeUiServerRestartRequest.WirelessAdb)
    }

    public open fun startAdb() {
        requestServerStart(PrivilegeUiServerRestartRequest.Adb)
    }

    public open fun onHostResume() {
        if (!hostResumeDispatchInProgress) {
            refreshHostInteractiveState()
        }
    }

    private fun refreshHostInteractiveState() {
        store.refreshLocalNetworkPermission()
        refreshBatteryOptimizationState()
        if (uiInteractionsEnabled) {
            adbActions.continuePendingPairingIfNotificationPermissionGranted()
            effectsCoordinator.refreshHostResumeState()
        }
        scheduleBatteryOptimizationStateRechecks()
    }

    internal fun dispatchHostResume() {
        effectsCoordinator.setHostResumed(true)
        refreshHostInteractiveState()
        hostResumeDispatchInProgress = true
        try {
            onHostResume()
        } finally {
            hostResumeDispatchInProgress = false
        }
    }


    internal fun dispatchHostPause() {
        effectsCoordinator.setHostResumed(false)
    }

    internal fun dispatchHostWindowFocus() {
        refreshHostInteractiveState()
    }

    internal fun dispatchHostWindowFocus(
        hasWindowFocus: Boolean,
        resumed: Boolean,
    ) {
        if (hasWindowFocus && resumed) {
            dispatchHostWindowFocus()
        }
    }

    private fun refreshBatteryOptimizationState() {
        batteryOptimizationRestrictedState.value = store.requireContext()
            .isBatteryOptimizationRestricted()
    }

    private fun scheduleBatteryOptimizationStateRechecks() {
        batteryOptimizationRefreshJob?.cancel()
        batteryOptimizationRefreshJob = viewModelScope.launch {
            for (delayMillis in BATTERY_OPTIMIZATION_RECHECK_DELAYS_MILLIS) {
                delay(delayMillis.milliseconds)
                refreshBatteryOptimizationState()
            }
        }
    }

    public open fun enableTcpMode() {
        if (!uiInteractionsEnabled) return
        viewModelScope.launch { adbActions.enableTcpMode() }
    }

    public fun disableTcpMode() {
        if (!uiInteractionsEnabled) return
        adbActions.disableTcpMode()
    }

    public fun restartTcpMode() {
        if (!uiInteractionsEnabled) return
        adbActions.restartTcpMode()
    }

    public open fun startStaticTcpAdb() {
        requestServerStart(PrivilegeUiServerRestartRequest.StaticTcpAdb)
    }

    public open fun confirmStaticTcpSwitch() {
        if (!uiInteractionsEnabled) return
        adbActions.confirmStaticTcpSwitch()
    }

    public open fun cancelStaticTcpSwitch() {
        if (!uiInteractionsEnabled) return
        adbActions.cancelStaticTcpSwitch()
    }

    public open fun authorizeOrStartExternal(providerId: String) {
        requestServerStart(PrivilegeUiServerRestartRequest.External(providerId))
    }

    internal fun refreshExternalStartLabels(context: Context) {
        store.refreshExternalStartLabels(context)
    }

    public open fun confirmServerRestart() {
        if (!uiInteractionsEnabled) return
        resolveServerRestartDecision(confirmed = true)
    }

    public open fun cancelServerRestart() {
        if (!uiInteractionsEnabled) return
        resolveServerRestartDecision(confirmed = false)
    }

    private fun requestServerStart(target: PrivilegeUiServerRestartRequest) {
        if (!uiInteractionsEnabled) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            requestServerStartInContext(target)
        }
    }

    private suspend fun requestServerStartInContext(target: PrivilegeUiServerRestartRequest) {
        val current = store.state.value
        if (
            current.busy ||
            current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            current.restartConfirmationTarget != null
        ) {
            return
        }
        val replaceConnectedServer =
            if (current.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
                if (!awaitServerRestartDecision(target)) return
                true
            } else {
                false
            }
        if (!uiInteractionsEnabled) return
        executeServerStart(target, replaceConnectedServer)
    }

    private suspend fun awaitServerRestartDecision(
        target: PrivilegeUiServerRestartRequest,
    ): Boolean {
        val response = CompletableDeferred<Boolean>()
        val pending = PendingServerRestartDecision(target, response)
        val registered = synchronized(serverRestartDecisionLock) {
            if (pendingServerRestartDecision != null) {
                false
            } else {
                pendingServerRestartDecision = pending
                true
            }
        }
        if (!registered) return false
        store.updateState { it.copy(restartConfirmationTarget = target) }
        return try {
            response.await()
        } finally {
            val stillPending = synchronized(serverRestartDecisionLock) {
                if (pendingServerRestartDecision === pending) {
                    pendingServerRestartDecision = null
                    true
                } else {
                    false
                }
            }
            if (stillPending) {
                clearServerRestartRequest(target)
            }
        }
    }

    private fun resolveServerRestartDecision(confirmed: Boolean) {
        val pending = synchronized(serverRestartDecisionLock) {
            pendingServerRestartDecision.also {
                pendingServerRestartDecision = null
            }
        } ?: return
        clearServerRestartRequest(pending.target)
        pending.response.complete(confirmed)
    }

    private fun clearServerRestartRequest(target: PrivilegeUiServerRestartRequest) {
        store.updateState { current ->
            if (current.restartConfirmationTarget == target) {
                current.copy(restartConfirmationTarget = null)
            } else {
                current
            }
        }
    }

    private fun executeServerStart(
        target: PrivilegeUiServerRestartRequest,
        replaceConnectedServer: Boolean,
    ) {
        when (target) {
            PrivilegeUiServerRestartRequest.Root ->
                runtimeActions.startRoot(replaceConnectedServer)
            PrivilegeUiServerRestartRequest.Adb ->
                adbActions.startAdb(
                    replaceConnectedServer,
                )
            PrivilegeUiServerRestartRequest.WirelessAdb ->
                adbActions.startWirelessAdb(
                    replaceConnectedServer,
                )
            PrivilegeUiServerRestartRequest.StaticTcpAdb ->
                adbActions.startStaticTcpAdb(
                    replaceConnectedServer,
                )
            is PrivilegeUiServerRestartRequest.External ->
                startExternal(target.providerId, replaceConnectedServer)
        }
    }

    private fun startExternal(
        providerId: String,
        replaceConnectedServer: Boolean,
    ) {
        if (!uiInteractionsEnabled) return
        if (externalAuthorizationJob?.isActive == true) return
        externalAuthorizationJob = viewModelScope.launch {
            externalStartActions.authorizeOrStartExternal(
                providerId = providerId,
                replaceConnectedServer = replaceConnectedServer,
            )
        }
    }

    private fun hasPermissionInteractionHost(): Boolean =
        permissionCoordinator.hasInteractionHost()

    internal fun registerPermissionHost(
        resumed: Boolean = true,
    ) {
        effectsCoordinator.setHostResumed(resumed)
        permissionCoordinator.registerHost()
    }

    internal fun unregisterPermissionHost(
        changingConfigurations: Boolean,
    ) {
        effectsCoordinator.setHostResumed(false)
        permissionCoordinator.unregisterHost(changingConfigurations)
        if (!changingConfigurations) {
            batteryOptimizationRequestJob?.cancel()
            accessCommands.cancelPending()
        }
    }

    private fun closeOwner() {
        ownerClosed.set(true)
        resolveServerRestartDecision(confirmed = false)
        batteryOptimizationRefreshJob?.cancel()
        batteryOptimizationRefreshJob = null
        runCatching { effectsCoordinator.close() }
        runtimeActions.close()
        runCatching { adbActions.close() }
        permissionCoordinator.close()
        accessCommands.cancelPending()
        authorizationCoordinator.close()
    }

    private data class PendingServerRestartDecision(
        val target: PrivilegeUiServerRestartRequest,
        val response: CompletableDeferred<Boolean>,
    )

    private fun disableDesiredEnabled() {
        runCatching {
            PrivilegeUi.setDesiredEnabled(false)
        }.onFailure { throwable ->
            store.showSnackbar(store.resourceText(R.string.priv_ui_auto_recovery_disable_failed))
            store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }
}

private val BATTERY_OPTIMIZATION_RECHECK_DELAYS_MILLIS = listOf(250L, 750L, 1_500L)
