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
    private var observerJob: Job? = null
    private var reconciledSilentCompletionSerial = 0L

    val startGateState: StateFlow<PrivilegeUiStartGateState> = PrivilegeUiStartGate.state
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val interactionsEnabled: Boolean
        get() = enabledState.value && interactiveStartOwner.canInteract(startGateState.value)

    fun initialize() {
        val gateState = startGateState.value
        reconciledSilentCompletionSerial = gateState.silentCompletionSerial
        enabledState.value = interactiveStartOwner.canInteract(gateState)
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

    fun effectsAllowed(gateState: PrivilegeUiStartGateState): Boolean =
        enabledState.value && interactiveStartOwner.canInteract(gateState)

    fun refreshHostResumeState() {
        coroutineScope.launch(CoroutineName("priv-ui-host-resume")) {
            runtimeActions.refreshAdbPermissionRestrictionStatus()
            when (store.state.value.selectedStartupMode) {
                PrivilegeUiStartupMode.ADB -> {
                    if (isPrivilegeUiWirelessAdbSupported()) {
                        adbActions.refreshWirelessAdbStatusNow()
                    }
                    if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                        adbActions.refreshTcpModeEnabledNow()
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
            store.updateState { it.copy(runtimeStatusLoaded = false) }
        }

        runtimeActions.refreshRuntimeStatus(useCurrentState = !silentCompletionChanged)
        if (!gate.isCurrent()) return
        store.updateState { it.copy(runtimeStatusLoaded = true) }

        reconciledSilentCompletionSerial = gate.silentCompletionSerial
        enabledState.value = true

        supervisorScope {
            launch {
                store.loadManualShellCommand()
                store.updateState { it.copy(manualShellStatusLoaded = true) }
            }
            store.state
                .map { it.selectedStartupMode }
                .distinctUntilChanged()
                .collectLatest(::pollSelectedMode)
        }
    }

    private suspend fun pollSelectedMode(mode: PrivilegeUiStartupMode): Unit = coroutineScope {
        when (mode) {
            PrivilegeUiStartupMode.ADB -> {
                if (!store.state.value.adbStatusLoaded) {
                    supervisorScope {
                        launch { adbActions.refreshAdbIdentityInfoNow() }
                        if (isPrivilegeUiWirelessAdbSupported()) {
                            launch { adbActions.refreshWirelessAdbStatusNow() }
                        }
                        if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                            launch { adbActions.refreshTcpModeEnabledNow() }
                        }
                    }
                    store.updateState { it.copy(adbStatusLoaded = true) }
                }
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
                if (!store.state.value.externalStartStatusLoaded) {
                    externalStartActions.refreshExternalStartStatusNow(providerId = null)
                    store.updateState { it.copy(externalStartStatusLoaded = true) }
                }
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
