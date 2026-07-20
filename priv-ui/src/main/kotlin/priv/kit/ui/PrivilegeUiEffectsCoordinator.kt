package priv.kit.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val reconciledSilentCompletionSerial = AtomicLong(Long.MIN_VALUE)
    private val enabledState = MutableStateFlow(false)
    private var observerJob: Job? = null
    private val wirelessStatusPolling = EffectPolling(
        shouldPoll = {
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
                isPrivilegeUiWirelessAdbSupported()
        },
        startPolling = adbActions::startWirelessAdbStatusPolling,
        stopAllPolling = adbActions::stopWirelessAdbStatusPolling,
    )
    private val tcpModeStatusPolling = EffectPolling(
        shouldPoll = {
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
                store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        },
        startPolling = adbActions::startTcpModeStatusPolling,
        stopAllPolling = adbActions::stopTcpModeStatusPolling,
    )
    private val externalStartStatusPolling = EffectPolling(
        shouldPoll = {
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL
        },
        startPolling = externalStartActions::startExternalStartStatusPolling,
        stopAllPolling = externalStartActions::stopExternalStartStatusPolling,
    )

    val startGateState: StateFlow<PrivilegeUiStartGateState> = PrivilegeUiStartGate.state
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val interactionsEnabled: Boolean
        get() = effectsAllowed(startGateState.value)

    fun initialize() {
        reconcile(startGateState.value)
        observerJob = coroutineScope.launch(
            Dispatchers.IO + CoroutineName("priv-ui-silent-start-completions"),
        ) {
            startGateState.collectLatest(::reconcile)
        }
    }

    fun effectsAllowed(gateState: PrivilegeUiStartGateState): Boolean =
        enabledState.value &&
            interactiveStartOwner.canInteract(gateState) &&
            reconciledSilentCompletionSerial.get() == gateState.silentCompletionSerial

    fun onStartupModeSelected() {
        syncStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    fun refreshHostResumeState() {
        runtimeActions.refreshAdbPermissionRestrictionStatus()
        syncStatusPolling()
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

    fun pauseStatusPolling() {
        synchronized(lock) {
            wirelessStatusPolling.stopAll()
            tcpModeStatusPolling.stopAll()
            externalStartStatusPolling.stopAll()
        }
    }

    fun stopWirelessStatusPolling() {
        wirelessStatusPolling.stopAll()
    }

    fun stopTcpModeStatusPolling() {
        tcpModeStatusPolling.stopAll()
    }

    fun stopExternalStartStatusPolling() {
        externalStartStatusPolling.stopAll()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observerJob?.cancel()
        observerJob = null
        synchronized(lock) {
            enabledState.value = false
            pauseStatusPolling()
        }
    }

    private fun reconcile(gateState: PrivilegeUiStartGateState) {
        if (closed.get() || !beginReconciliation(gateState)) return
        runtimeActions.refreshRuntimeStatus()
        completeReconciliation(gateState)
    }

    private fun beginReconciliation(gateState: PrivilegeUiStartGateState): Boolean =
        synchronized(lock) {
            if (!interactiveStartOwner.canInteract(gateState)) {
                enabledState.value = false
                pauseStatusPolling()
                return@synchronized false
            }
            if (
                reconciledSilentCompletionSerial.get() == gateState.silentCompletionSerial &&
                enabledState.value
            ) {
                return@synchronized false
            }

            enabledState.value = false
            pauseStatusPolling()
            true
        }

    private fun completeReconciliation(gateState: PrivilegeUiStartGateState) {
        synchronized(lock) {
            if (closed.get()) return
            val currentGateState = startGateState.value
            if (
                interactiveStartOwner.canInteract(currentGateState) &&
                currentGateState.silentCompletionSerial == gateState.silentCompletionSerial
            ) {
                reconciledSilentCompletionSerial.set(gateState.silentCompletionSerial)
                resumeAfterReconciliation()
                val resumedGateState = startGateState.value
                if (
                    interactiveStartOwner.canInteract(resumedGateState) &&
                    resumedGateState.silentCompletionSerial == gateState.silentCompletionSerial
                ) {
                    enabledState.value = true
                }
            }
        }
    }

    private fun resumeAfterReconciliation() {
        store.loadManualShellCommand()
        externalStartActions.refreshExternalStartStatus()
        adbActions.refreshAdbIdentityInfo()
        syncStatusPolling(allowDuringReconciliation = true)
        refreshTcpModeEnabledIfSelected(allowDuringReconciliation = true)
    }

    private fun syncStatusPolling(allowDuringReconciliation: Boolean = false) {
        wirelessStatusPolling.sync(allowDuringReconciliation)
        tcpModeStatusPolling.sync(allowDuringReconciliation)
        externalStartStatusPolling.sync(allowDuringReconciliation)
    }

    private fun refreshTcpModeEnabledIfSelected(allowDuringReconciliation: Boolean = false) {
        if (!allowDuringReconciliation && !interactionsEnabled) return
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            adbActions.refreshTcpModeEnabled()
        }
    }

    private inner class EffectPolling(
        private val shouldPoll: () -> Boolean,
        private val startPolling: () -> AutoCloseable,
        private val stopAllPolling: () -> Unit,
    ) {
        private var handle: AutoCloseable? = null

        fun sync(allowDuringReconciliation: Boolean = false) {
            synchronized(lock) {
                if (!allowDuringReconciliation && !interactionsEnabled) {
                    stopAllLocked()
                } else if (shouldPoll()) {
                    if (handle == null) handle = startPolling()
                } else {
                    releaseLocked()
                }
            }
        }

        fun stopAll() {
            synchronized(lock) {
                stopAllLocked()
            }
        }

        private fun stopAllLocked() {
            releaseLocked()
            stopAllPolling()
        }

        private fun releaseLocked() {
            handle?.close()
            handle = null
        }
    }
}
