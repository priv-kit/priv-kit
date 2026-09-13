package priv.kit.ui

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiInteractiveStartOwner
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartGateState
import priv.kit.ui.runtime.loadManualShellCommand
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported

internal class PrivilegeUiEffectsCoordinator(
    private val store: PrivilegeUiViewModelStore,
    private val interactiveStartOwner: PrivilegeUiInteractiveStartOwner,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val adbActions: PrivilegeUiAdbActions,
    private val externalStartActions: PrivilegeUiExternalStartActions,
    private val coroutineScope: CoroutineScope,
) : AutoCloseable {
    private val enabledState = MutableStateFlow(false)
    private var initialLoadCompleted = false
    private var observerJob: Job? = null
    private var reconciledSilentCompletionSerial = 0L

    val startGateState: StateFlow<PrivilegeUiStartGateState> = PrivilegeUiStartGate.state
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    fun initialize() {
        val gateState = startGateState.value
        reconciledSilentCompletionSerial = gateState.silentCompletionSerial
        enabledState.value = false
        observerJob = coroutineScope.launch(CoroutineName("priv-ui-effects")) {
            startGateState
                .map { state ->
                    EffectsGate(
                        canInteract = interactiveStartOwner.canInteract(state),
                        silentCompletionSerial = state.silentCompletionSerial,
                    )
                }
                .distinctUntilChanged()
                .collectLatest(::runEffects)
        }
    }

    fun canInteract(
        gateState: PrivilegeUiStartGateState = startGateState.value,
    ): Boolean =
        interactiveStartOwner.canInteract(gateState)

    fun refreshHostResumeState() {
        coroutineScope.launch(CoroutineName("priv-ui-host-resume")) {
            runtimeActions.refreshPermissionRestrictionStatus()
            when (store.state.value.selectedStartupMode) {
                PrivilegeUiStartupMode.ADB -> {
                    if (isPrivilegeUiWirelessAdbSupported()) {
                        adbActions.refreshWirelessAdbStatusNow(markChecking = false)
                    }
                    if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                        adbActions.refreshTcpModeEnabledNow(markChecking = false)
                    }
                }
                PrivilegeUiStartupMode.EXTERNAL ->
                    externalStartActions.refreshExternalStartStatusNow(providerId = null)
                else -> Unit
            }
        }
    }

    override fun close() {
        enabledState.value = false
        observerJob?.cancel()
        observerJob = null
    }

    private suspend fun runEffects(gate: EffectsGate) {
        if (!gate.canInteract) {
            enabledState.value = false
            return
        }
        val silentCompletionChanged =
            gate.silentCompletionSerial != reconciledSilentCompletionSerial
        if (silentCompletionChanged) {
            enabledState.value = false
        }

        val loadingInitialState = !initialLoadCompleted
        if (loadingInitialState) {
            loadImmediateInitialState(
                useCurrentRuntimeState = !silentCompletionChanged,
            )
        } else {
            runtimeActions.refreshRuntimeStatus(
                useCurrentState = !silentCompletionChanged,
            )
        }
        if (!gate.isCurrent()) return

        reconciledSilentCompletionSerial = gate.silentCompletionSerial
        if (loadingInitialState) {
            initialLoadCompleted = true
        }
        enabledState.value = true

        supervisorScope {
            launch(CoroutineName("priv-ui-deferred-initial-state")) {
                loadDeferredInitialState()
            }
            store.state
                .map { it.selectedStartupMode }
                .distinctUntilChanged()
                .collectLatest(::pollSelectedMode)
        }
    }

    private suspend fun loadImmediateInitialState(
        useCurrentRuntimeState: Boolean,
    ): Unit = supervisorScope {
        val startupModes = store.state.value.startupModes
        launch {
            runtimeActions.refreshRuntimeStatus(
                useCurrentState = useCurrentRuntimeState,
            )
            runtimeActions.refreshPermissionRestrictionStatusNow()
        }
        if (PrivilegeUiStartupMode.MANUAL_SHELL in startupModes) {
            launch { store.loadManualShellCommand() }
        }
    }

    private suspend fun loadDeferredInitialState(): Unit = supervisorScope {
        val state = store.state.value
        val startupModes = state.startupModes
        if (PrivilegeUiStartupMode.ADB in startupModes) {
            if (
                isPrivilegeUiWirelessAdbSupported() &&
                !state.wirelessAdbStatusLoaded
            ) {
                launch {
                    adbActions.refreshWirelessAdbStatusNow(markChecking = false)
                }
            }
            if (
                store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
                !state.staticTcp.loaded
            ) {
                launch {
                    adbActions.refreshTcpModeEnabledNow(markChecking = false)
                }
            }
        }
        if (
            PrivilegeUiStartupMode.EXTERNAL in startupModes &&
            state.externalStartItems.any { !it.statusLoaded }
        ) {
            launch {
                externalStartActions.refreshExternalStartStatusNow(providerId = null)
            }
        }
    }

    private suspend fun pollSelectedMode(mode: PrivilegeUiStartupMode): Unit = coroutineScope {
        when (mode) {
            PrivilegeUiStartupMode.ADB -> {
                if (isPrivilegeUiWirelessAdbSupported()) {
                    launch(CoroutineName("priv-ui-wireless-adb-status")) {
                        adbActions.pollWirelessAdbStatus()
                    }
                }
                if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                    launch(CoroutineName("priv-ui-tcp-mode-status")) {
                        adbActions.pollTcpModeStatus()
                    }
                }
            }
            PrivilegeUiStartupMode.EXTERNAL -> {
                launch(CoroutineName("priv-ui-external-start-status")) {
                    externalStartActions.pollExternalStartStatus()
                }
            }
            else -> Unit
        }
        awaitCancellation()
    }

    private fun EffectsGate.isCurrent(): Boolean {
        val current = startGateState.value
        return canInteract &&
            current.silentCompletionSerial == silentCompletionSerial &&
            interactiveStartOwner.canInteract(current)
    }

    private data class EffectsGate(
        val canInteract: Boolean,
        val silentCompletionSerial: Long,
    )
}
