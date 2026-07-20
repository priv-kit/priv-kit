package priv.kit.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import priv.kit.PrivilegeServerInfo
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiDirectStartTarget
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartGateState
import priv.kit.ui.runtime.copyManualShellCommand
import priv.kit.ui.runtime.directStartTargets
import priv.kit.ui.runtime.loadManualShellCommand
import priv.kit.ui.state.PrivilegeUiNoopCloseable
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.copyToClipboard
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public open class PrivilegeUiViewModel @JvmOverloads public constructor(
    application: Application,
    public val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) : AndroidViewModel(application) {
    private val store = PrivilegeUiViewModelStore(application)
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
    private val uiEffectsLock = Any()
    private var wirelessStatusPollingHandle: AutoCloseable? = null
    private var tcpModeStatusPollingHandle: AutoCloseable? = null
    private var externalStartStatusPollingHandle: AutoCloseable? = null
    private var batteryOptimizationRefreshJob: Job? = null
    private var deliveredConnectionSerial = 0L
    private var hostResumeDispatchInProgress = false
    private val permissionRequestLock = Any()
    private val attachedPermissionHostIds = mutableSetOf<String>()
    private val detachedPermissionHostSerials = mutableMapOf<String, Long>()
    private val permissionHostRebindJobs = mutableMapOf<String, Job>()
    private val permissionHostDetachSerial = AtomicLong(0L)
    private val queuedPermissionRequests = ArrayDeque<PrivilegeUiPermissionRequest>()
    private val activePermissionRequestState = MutableStateFlow<PrivilegeUiPermissionRequest?>(null)
    private val batteryOptimizationPromptVisibleState = MutableStateFlow(false)
    private val uiEffectsEnabledState = MutableStateFlow(false)
    private val reconciledSilentCompletionSerial = AtomicLong(Long.MIN_VALUE)
    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    internal val startGateState: StateFlow<PrivilegeUiStartGateState> =
        PrivilegeUiStartGate.state
    internal val uiEffectsEnabled: StateFlow<Boolean> = uiEffectsEnabledState.asStateFlow()
    internal val uiInteractionsEnabled: Boolean
        get() = uiEffectsAllowed(startGateState.value)
    internal val snackbarMessages: SharedFlow<String> = store.snackbarMessages
    internal val permissionRequests: Flow<PrivilegeUiPermissionRequest> =
        activePermissionRequestState.filterNotNull()
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
        runtimeActions.installRuntimeWatchers()
        reconcileInitialUiEffects(startGateState.value)
        observeSilentStartCompletions()

        refreshBatteryOptimizationState()
    }

    private fun observeSilentStartCompletions() {
        viewModelScope.launch(
            Dispatchers.IO + CoroutineName("priv-ui-silent-start-completions"),
        ) {
            startGateState.collectLatest { gateState ->
                reconcileUiEffects(gateState)
            }
        }
    }

    private fun reconcileInitialUiEffects(gateState: PrivilegeUiStartGateState) {
        if (!beginUiEffectsReconciliation(gateState)) return
        runtimeActions.refreshRuntimeStatus()
        completeUiEffectsReconciliation(gateState)
    }

    private suspend fun reconcileUiEffects(gateState: PrivilegeUiStartGateState) {
        if (!beginUiEffectsReconciliation(gateState)) return
        runtimeActions.refreshRuntimeStatus()
        completeUiEffectsReconciliation(gateState)
    }

    private fun beginUiEffectsReconciliation(gateState: PrivilegeUiStartGateState): Boolean =
        synchronized(uiEffectsLock) {
            if (!interactiveStartOwner.canInteract(gateState)) {
                uiEffectsEnabledState.value = false
                pauseUiEffectPolling()
                return@synchronized false
            }
            if (
                reconciledSilentCompletionSerial.get() == gateState.silentCompletionSerial &&
                uiEffectsEnabledState.value
            ) {
                return@synchronized false
            }

            uiEffectsEnabledState.value = false
            pauseUiEffectPolling()
            true
        }

    private fun completeUiEffectsReconciliation(gateState: PrivilegeUiStartGateState) {
        synchronized(uiEffectsLock) {
            val currentGateState = startGateState.value
            if (
                interactiveStartOwner.canInteract(currentGateState) &&
                currentGateState.silentCompletionSerial == gateState.silentCompletionSerial
            ) {
                reconciledSilentCompletionSerial.set(gateState.silentCompletionSerial)
                resumeUiEffectsAfterReconciliation()
                val resumedGateState = startGateState.value
                if (
                    interactiveStartOwner.canInteract(resumedGateState) &&
                    resumedGateState.silentCompletionSerial == gateState.silentCompletionSerial
                ) {
                    uiEffectsEnabledState.value = true
                }
            }
        }
    }

    internal fun uiEffectsAllowed(gateState: PrivilegeUiStartGateState): Boolean =
        uiEffectsEnabledState.value &&
            interactiveStartOwner.canInteract(gateState) &&
            reconciledSilentCompletionSerial.get() == gateState.silentCompletionSerial

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
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun startRoot() {
        if (!uiInteractionsEnabled) return
        runtimeActions.startRoot()
    }

    /** Starts the foreground provider sequence used by the service-status action. */
    @Suppress("DEPRECATION")
    public open fun startInteractive() {
        if (!uiInteractionsEnabled) return
        startAvailable()
    }

    @Deprecated("Use startInteractive()", ReplaceWith("startInteractive()"))
    public open fun startAvailable() {
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
        runtimeActions.stopServer()
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
    ) {
        val request = synchronized(permissionRequestLock) {
            val activeRequest =
                activePermissionRequestState.value as? PrivilegeUiPermissionRequest.Notification
            activeRequest
                ?.takeIf { it.tryClaimLaunchedCompletion(hostId) }
                ?.also { advancePermissionRequestLocked() }
        } ?: return
        handleNotificationPermissionResult(request, permissionState)
    }

    internal fun completeUnlaunchedNotificationPermissionRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest.Notification,
        permissionState: PrivilegeUiPermissionState,
    ) {
        val claimed = synchronized(permissionRequestLock) {
            if (
                hostId !in attachedPermissionHostIds ||
                activePermissionRequestState.value !== request ||
                !request.tryClaimUnlaunchedCompletion()
            ) {
                false
            } else {
                advancePermissionRequestLocked()
                true
            }
        }
        if (claimed) handleNotificationPermissionResult(request, permissionState)
    }

    private fun handleNotificationPermissionResult(
        request: PrivilegeUiPermissionRequest.Notification,
        permissionState: PrivilegeUiPermissionState,
    ) {
        try {
            adbActions.handleNotificationPermissionResult(permissionState)
        } finally {
            request.close()
        }
    }

    internal fun completeLocalNetworkPermissionRequest(hostId: String) {
        val request = synchronized(permissionRequestLock) {
            val activeRequest =
                activePermissionRequestState.value as? PrivilegeUiPermissionRequest.LocalNetwork
            activeRequest
                ?.takeIf { it.tryClaimLaunchedCompletion(hostId) }
                ?.also { advancePermissionRequestLocked() }
        } ?: return
        request.close()
    }

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
            refreshHostResumeState()
        } else {
            pauseUiEffectPolling()
        }
        scheduleBatteryOptimizationStateRechecks()
    }

    private fun refreshHostResumeState() {
        runtimeActions.refreshAdbPermissionRestrictionStatus()
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            isPrivilegeUiWirelessAdbSupported()
        ) {
            adbActions.refreshWirelessAdbStatus()
        }
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
            externalStartActions.refreshExternalStartStatus()
        }
        refreshTcpModeEnabledIfSelected()
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
        synchronized(uiEffectsLock) {
            wirelessStatusPollingHandle?.close()
            wirelessStatusPollingHandle = null
            adbActions.stopWirelessAdbStatusPolling()
        }
    }

    public open fun startTcpModeStatusPolling(): AutoCloseable =
        if (uiInteractionsEnabled) {
            adbActions.startTcpModeStatusPolling()
        } else {
            PrivilegeUiNoopCloseable
        }

    public open fun stopTcpModeStatusPolling() {
        synchronized(uiEffectsLock) {
            tcpModeStatusPollingHandle?.close()
            tcpModeStatusPollingHandle = null
            adbActions.stopTcpModeStatusPolling()
        }
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
        synchronized(uiEffectsLock) {
            externalStartStatusPollingHandle?.close()
            externalStartStatusPollingHandle = null
            externalStartActions.stopExternalStartStatusPolling()
        }
    }

    public open fun authorizeOrStartExternal(providerId: String) {
        if (!uiInteractionsEnabled) return
        externalStartActions.authorizeOrStartExternal(providerId)
    }

    private fun syncWirelessAdbStatusPolling(allowDuringReconciliation: Boolean = false) {
        synchronized(uiEffectsLock) {
            if (!allowDuringReconciliation && !uiInteractionsEnabled) {
                wirelessStatusPollingHandle?.close()
                wirelessStatusPollingHandle = null
                adbActions.stopWirelessAdbStatusPolling()
                return@synchronized
            }
            if (
                store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
                isPrivilegeUiWirelessAdbSupported()
            ) {
                if (wirelessStatusPollingHandle == null) {
                    wirelessStatusPollingHandle = adbActions.startWirelessAdbStatusPolling()
                }
            } else {
                wirelessStatusPollingHandle?.close()
                wirelessStatusPollingHandle = null
            }
        }
    }

    private fun syncTcpModeStatusPolling(allowDuringReconciliation: Boolean = false) {
        synchronized(uiEffectsLock) {
            if (!allowDuringReconciliation && !uiInteractionsEnabled) {
                tcpModeStatusPollingHandle?.close()
                tcpModeStatusPollingHandle = null
                adbActions.stopTcpModeStatusPolling()
                return@synchronized
            }
            if (
                store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
                store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
            ) {
                if (tcpModeStatusPollingHandle == null) {
                    tcpModeStatusPollingHandle = adbActions.startTcpModeStatusPolling()
                }
            } else {
                tcpModeStatusPollingHandle?.close()
                tcpModeStatusPollingHandle = null
            }
        }
    }

    private fun syncExternalStartStatusPolling(allowDuringReconciliation: Boolean = false) {
        synchronized(uiEffectsLock) {
            if (!allowDuringReconciliation && !uiInteractionsEnabled) {
                externalStartStatusPollingHandle?.close()
                externalStartStatusPollingHandle = null
                externalStartActions.stopExternalStartStatusPolling()
                return@synchronized
            }
            if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
                if (externalStartStatusPollingHandle == null) {
                    externalStartStatusPollingHandle =
                        externalStartActions.startExternalStartStatusPolling()
                }
            } else {
                externalStartStatusPollingHandle?.close()
                externalStartStatusPollingHandle = null
            }
        }
    }

    private fun refreshTcpModeEnabledIfSelected(allowDuringReconciliation: Boolean = false) {
        if (!allowDuringReconciliation && !uiInteractionsEnabled) return
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            adbActions.refreshTcpModeEnabled()
        }
    }

    private fun pauseUiEffectPolling() {
        synchronized(uiEffectsLock) {
            wirelessStatusPollingHandle?.close()
            wirelessStatusPollingHandle = null
            adbActions.stopWirelessAdbStatusPolling()
            tcpModeStatusPollingHandle?.close()
            tcpModeStatusPollingHandle = null
            adbActions.stopTcpModeStatusPolling()
            externalStartStatusPollingHandle?.close()
            externalStartStatusPollingHandle = null
            externalStartActions.stopExternalStartStatusPolling()
        }
    }

    private fun resumeUiEffectsAfterReconciliation() {
        store.loadManualShellCommand()
        externalStartActions.refreshExternalStartStatus()
        adbActions.refreshAdbIdentityInfo()
        syncWirelessAdbStatusPolling(allowDuringReconciliation = true)
        syncTcpModeStatusPolling(allowDuringReconciliation = true)
        syncExternalStartStatusPolling(allowDuringReconciliation = true)
        refreshTcpModeEnabledIfSelected(allowDuringReconciliation = true)
    }

    private fun requestNotificationPermission(): Boolean {
        if (!uiInteractionsEnabled) return false
        val permit = acquireInteractivePermit() ?: return false
        return enqueuePermissionRequest(PrivilegeUiPermissionRequest.Notification(permit))
    }

    private fun hasPermissionInteractionHost(): Boolean =
        synchronized(permissionRequestLock) {
            attachedPermissionHostIds.isNotEmpty() || detachedPermissionHostSerials.isNotEmpty()
        }

    private fun requestLocalNetworkPermission(permission: String) {
        if (!uiInteractionsEnabled) return
        val permit = acquireInteractivePermit() ?: return
        enqueuePermissionRequest(PrivilegeUiPermissionRequest.LocalNetwork(permission, permit))
    }

    internal fun registerPermissionHost(hostId: String) {
        val rebindJob = synchronized(permissionRequestLock) {
            if (ownerClosed.get()) return
            attachedPermissionHostIds += hostId
            detachedPermissionHostSerials.remove(hostId)
            permissionHostRebindJobs.remove(hostId)
        }
        rebindJob?.cancel()
    }

    internal fun unregisterPermissionHost(
        hostId: String,
        changingConfigurations: Boolean,
    ) {
        if (changingConfigurations) {
            detachPermissionHostForConfigurationChange(hostId)
            return
        }
        val (requestsToCancel, noInteractionHostsRemain) = synchronized(permissionRequestLock) {
            if (!attachedPermissionHostIds.remove(hostId)) return
            val activeRequest = activePermissionRequestState.value
            val noHostsRemain =
                attachedPermissionHostIds.isEmpty() && detachedPermissionHostSerials.isEmpty()
            val requests = when {
                noHostsRemain ->
                    clearPermissionRequestsLocked()
                activeRequest?.wasLaunchedBy(hostId) == true -> {
                    if (activeRequest.tryClaimCancellation(hostId)) {
                        advancePermissionRequestLocked()
                        listOf(activeRequest)
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }
            requests to noHostsRemain
        }
        finishPermissionHostCleanup(requestsToCancel, noInteractionHostsRemain)
    }

    private fun detachPermissionHostForConfigurationChange(hostId: String) {
        val serial = synchronized(permissionRequestLock) {
            if (!attachedPermissionHostIds.remove(hostId) || ownerClosed.get()) return
            permissionHostRebindJobs.remove(hostId)?.cancel()
            permissionHostDetachSerial.incrementAndGet().also { detachSerial ->
                detachedPermissionHostSerials[hostId] = detachSerial
            }
        }
        val rebindJob = viewModelScope.launch(
            CoroutineName("priv-ui-permission-host-rebind"),
        ) {
            delay(PERMISSION_HOST_REBIND_GRACE_MILLIS)
            expireDetachedPermissionHost(hostId, serial)
        }
        synchronized(permissionRequestLock) {
            if (detachedPermissionHostSerials[hostId] == serial && !ownerClosed.get()) {
                permissionHostRebindJobs[hostId] = rebindJob
            } else {
                rebindJob.cancel()
            }
        }
    }

    private fun expireDetachedPermissionHost(
        hostId: String,
        serial: Long,
    ) {
        val (requestsToCancel, noInteractionHostsRemain) = synchronized(permissionRequestLock) {
            if (detachedPermissionHostSerials[hostId] != serial) return
            detachedPermissionHostSerials.remove(hostId)
            permissionHostRebindJobs.remove(hostId)
            val noHostsRemain =
                attachedPermissionHostIds.isEmpty() && detachedPermissionHostSerials.isEmpty()
            val requests = buildList {
                val activeRequest = activePermissionRequestState.value
                if (
                    activeRequest?.wasLaunchedBy(hostId) == true &&
                    activeRequest.tryClaimCancellation(hostId)
                ) {
                    advancePermissionRequestLocked()
                    add(activeRequest)
                }
                if (noHostsRemain) {
                    addAll(clearPermissionRequestsLocked())
                }
            }.distinct()
            requests to noHostsRemain
        }
        finishPermissionHostCleanup(requestsToCancel, noInteractionHostsRemain)
    }

    private fun enqueuePermissionRequest(request: PrivilegeUiPermissionRequest): Boolean {
        synchronized(permissionRequestLock) {
            if (ownerClosed.get() || attachedPermissionHostIds.isEmpty()) {
                request.close()
                return false
            }
            if (activePermissionRequestState.value == null) {
                activePermissionRequestState.value = request
            } else {
                queuedPermissionRequests += request
            }
        }
        return true
    }

    internal fun completePermissionRequest(request: PrivilegeUiPermissionRequest) {
        synchronized(permissionRequestLock) {
            request.tryClaimCancellation()
            if (activePermissionRequestState.value === request) {
                advancePermissionRequestLocked()
            } else {
                queuedPermissionRequests.remove(request)
            }
        }
        request.close()
    }

    internal fun cancelPermissionRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest,
    ) {
        val removed = synchronized(permissionRequestLock) {
            if (
                activePermissionRequestState.value !== request ||
                !request.tryClaimCancellation(hostId)
            ) {
                false
            } else {
                advancePermissionRequestLocked()
                true
            }
        }
        if (removed) cancelPermissionRequests(listOf(request))
    }

    private fun advancePermissionRequestLocked() {
        activePermissionRequestState.value = queuedPermissionRequests.removeFirstOrNull()
    }

    private fun clearPermissionRequestsLocked(): List<PrivilegeUiPermissionRequest> =
        buildList {
            activePermissionRequestState.value?.let(::add)
            addAll(queuedPermissionRequests)
        }.also {
            it.forEach { request -> request.tryClaimCancellation() }
            activePermissionRequestState.value = null
            queuedPermissionRequests.clear()
        }

    private fun cancelPermissionRequests(requests: List<PrivilegeUiPermissionRequest>) {
        if (requests.any { it is PrivilegeUiPermissionRequest.Notification }) {
            adbActions.cancelNotificationPermissionRequest()
        }
        requests.forEach(PrivilegeUiPermissionRequest::close)
    }

    private fun finishPermissionHostCleanup(
        requests: List<PrivilegeUiPermissionRequest>,
        noInteractionHostsRemain: Boolean,
    ) {
        cancelPermissionRequests(requests)
        if (noInteractionHostsRemain) {
            adbActions.cancelPairingWithoutInteractionHost()
        }
    }

    override fun onCleared() {
        closeOwner()
    }

    private fun closeOwner() {
        if (!ownerClosed.compareAndSet(false, true)) return
        batteryOptimizationRefreshJob?.cancel()
        batteryOptimizationRefreshJob = null
        runtimeActions.close()
        runCatching { externalStartActions.close() }
        runCatching { adbActions.close() }
        val (permissionRequestsToClose, rebindJobsToCancel) = synchronized(permissionRequestLock) {
            attachedPermissionHostIds.clear()
            detachedPermissionHostSerials.clear()
            val rebindJobs = permissionHostRebindJobs.values.toList()
            permissionHostRebindJobs.clear()
            clearPermissionRequestsLocked() to rebindJobs
        }
        rebindJobsToCancel.forEach { job -> job.cancel() }
        permissionRequestsToClose.forEach(PrivilegeUiPermissionRequest::close)
        runCatching { store.close() }
    }
}

private const val PERMISSION_HOST_REBIND_GRACE_MILLIS = 10_000L
private val BATTERY_OPTIMIZATION_RECHECK_DELAYS_MILLIS = listOf(250L, 750L, 1_500L)

internal sealed class PrivilegeUiPermissionRequest(
    private val interactionPermit: AutoCloseable,
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var completionClaimed = false
    private var launchedHostId: String? = null
    private val completion = CompletableDeferred<Unit>()

    internal val wasLaunched: Boolean
        get() = synchronized(stateLock) { launchedHostId != null }

    internal fun tryMarkLaunched(hostId: String): Boolean =
        synchronized(stateLock) {
            if (closed || completionClaimed || launchedHostId != null) {
                false
            } else {
                launchedHostId = hostId
                true
            }
        }

    internal fun wasLaunchedBy(hostId: String): Boolean =
        synchronized(stateLock) { launchedHostId == hostId }

    internal fun tryClaimLaunchedCompletion(hostId: String): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == hostId)
        }

    internal fun tryClaimUnlaunchedCompletion(): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == null)
        }

    internal fun tryClaimCancellation(hostId: String): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == null || launchedHostId == hostId)
        }

    internal fun tryClaimCancellation(): Boolean =
        synchronized(stateLock) { tryClaimCompletionLocked(true) }

    internal suspend fun awaitCompletion() {
        completion.await()
    }

    final override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        runCatching { interactionPermit.close() }
        completion.complete(Unit)
    }

    private fun tryClaimCompletionLocked(ownerMatches: Boolean): Boolean {
        if (closed || completionClaimed || !ownerMatches) return false
        completionClaimed = true
        return true
    }

    class Notification(
        interactionPermit: AutoCloseable,
    ) : PrivilegeUiPermissionRequest(interactionPermit)

    class LocalNetwork(
        val permission: String,
        interactionPermit: AutoCloseable,
    ) : PrivilegeUiPermissionRequest(interactionPermit)
}
