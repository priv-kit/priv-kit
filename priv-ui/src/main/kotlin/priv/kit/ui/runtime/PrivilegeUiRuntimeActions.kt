package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val shutdownServer: () -> Unit = { Privilege.shutdownServer() },
    private val isAdbPermissionRestricted: () -> Boolean =
        Privilege::isAdbPermissionRestricted,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var nextStopOperationId = 0L
    private val activeStopOperationIds = mutableSetOf<Long>()
    private val adbRestrictionRefreshGeneration = AtomicLong(0L)
    private val runtimeStartCoordinator = PrivilegeUiRuntimeStartCoordinator(
        store = store,
        coroutineScope = coroutineScope,
        isClosed = closed::get,
        publishConnectedServer = ::publishConnectedServerLocked,
    )

    internal val isClosed: Boolean
        get() = closed.get()

    fun startRoot() {
        runServerStart(rootStartAttempt())
    }

    fun rootStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect =
        PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_starting_root),
            startupSource = store.text(R.string.priv_ui_auth_method_root),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
        ) {
            Privilege.startRoot(
                timeoutMillis = store.config.startTimeoutMillis,
                startupLogListener = startupLogListener,
            )
        }

    fun stopServer() {
        var operationId = 0L
        var connectionSerial = 0L
        synchronized(store) {
            val current = store.state.value
            if (
                closed.get() ||
                activeStopOperationIds.isNotEmpty() ||
                current.busy ||
                current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
                current.runtimeStatus != PrivilegeUiRuntimeStatus.CONNECTED
            ) {
                return
            }
            operationId = ++nextStopOperationId
            connectionSerial = current.connectionSerial
            activeStopOperationIds += operationId
            store.serverShutdownRequestedByOwner = true
            store.updateState { it.copy(busy = true) }
        }
        val message = store.text(R.string.priv_ui_stopping_service)
        store.appendLog(message)
        val job = coroutineScope.launch(Dispatchers.IO + CoroutineName("priv-ui-stop-server")) {
            try {
                runInterruptible { shutdownServer() }
                synchronized(store) {
                    if (!ownsStopOperationLocked(operationId, connectionSerial)) return@synchronized
                    store.updateState {
                        it.copy(
                            busy = false,
                            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                            runtimeStartSource = null,
                            runtimeStartProviderId = null,
                            serverInfo = null,
                            adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                            runtimeProgressMessage = null,
                        )
                    }
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (throwable: Throwable) {
                synchronized(store) {
                    if (!ownsStopOperationLocked(operationId, connectionSerial)) return@synchronized
                    store.updateState {
                        it.copy(
                            busy = false,
                            runtimeStartSource = null,
                            runtimeStartProviderId = null,
                            runtimeProgressMessage = null,
                        )
                    }
                    store.showFailure(PrivilegeUiFailureKind.STOP_SERVICE_FAILED)
                    store.appendLog(throwable.toPrivilegeUiDiagnosticString())
                }
            }
        }
        job.invokeOnCompletion {
            synchronized(store) {
                activeStopOperationIds -= operationId
                store.serverShutdownRequestedByOwner = activeStopOperationIds.isNotEmpty()
            }
        }
    }

    private fun ownsStopOperationLocked(operationId: Long, connectionSerial: Long): Boolean =
        !closed.get() &&
            operationId in activeStopOperationIds &&
            store.state.value.connectionSerial == connectionSerial

    fun stopCurrentStart() {
        runtimeStartCoordinator.stopCurrentStart()
    }

    fun refreshRuntimeStatus() {
        if (closed.get()) return
        val observedConnectionSerial = store.state.value.connectionSerial
        try {
            if (Privilege.pingServer()) {
                connectServer(Privilege.getServerInfo())
            } else {
                updateDisconnectedIfIdle(observedConnectionSerial)
            }
        } catch (_: Throwable) {
            updateDisconnectedIfIdle(observedConnectionSerial)
        }
    }

    fun refreshAdbPermissionRestrictionStatus() {
        synchronized(store) {
            val current = store.state.value
            if (
                closed.get() ||
                current.runtimeStatus != PrivilegeUiRuntimeStatus.CONNECTED ||
                current.serverInfo == null
            ) {
                return
            }
            scheduleAdbPermissionRestrictionRefresh(current.connectionSerial)
        }
    }

    fun installRuntimeWatchers() {
        store.serverConnectedListener?.close()
        store.serverConnectedListener = Privilege.addServerConnectedListener(::connectServer)
        store.serverDisconnectedWatcher?.close()
        store.serverDisconnectedWatcher = Privilege.addServerDisconnectedListener {
            handleServerDisconnected()
        }
    }

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect) {
        runtimeStartCoordinator.runServerStart(attempt)
    }

    fun runServerStartRequest(attempt: PrivilegeUiRuntimeStartAttempt.Request) {
        runtimeStartCoordinator.runServerStartRequest(attempt)
    }

    fun runServerStartWorkflow(attempt: PrivilegeUiRuntimeStartAttempt.Workflow) {
        runtimeStartCoordinator.runServerStartWorkflow(attempt)
    }

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>) {
        runtimeStartCoordinator.runServerStartFallback(attempts)
    }

    fun <T> runBusy(
        message: String,
        failureKind: PrivilegeUiFailureKind,
        action: () -> T,
        onFailure: ((Throwable) -> Unit)? = null,
        onSuccess: (T) -> String,
    ) {
        val current = store.state.value
        if (
            closed.get() ||
            current.busy ||
            current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE
        ) {
            return
        }
        store.updateState { it.copy(busy = true) }
        store.appendLog(message)
        coroutineScope.launch(Dispatchers.IO + CoroutineName("priv-ui-runtime-busy")) {
            try {
                val result = runInterruptible { action() }
                if (!closed.get()) {
                    val resultMessage = onSuccess(result)
                    store.updateState { it.copy(busy = false) }
                    store.appendLog(resultMessage)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (throwable: Throwable) {
                if (!closed.get()) {
                    onFailure?.invoke(throwable)
                    store.updateState { it.copy(busy = false) }
                    store.showFailure(failureKind)
                    store.appendLog(throwable.toPrivilegeUiDiagnosticString())
                }
            }
        }
    }

    override fun close() {
        if (!synchronized(store) { closed.compareAndSet(false, true) }) return
        adbRestrictionRefreshGeneration.incrementAndGet()
        runtimeStartCoordinator.close()
        runCatching { store.serverConnectedListener?.close() }
        runCatching { store.serverDisconnectedWatcher?.close() }
        store.serverConnectedListener = null
        store.serverDisconnectedWatcher = null
    }

    private fun connectServer(serverInfo: PrivilegeServerInfo) {
        runtimeStartCoordinator.handleServerConnected(serverInfo)
    }

    private fun handleServerDisconnected() {
        if (closed.get()) return
        val message = if (store.serverShutdownRequestedByOwner) {
            store.text(R.string.priv_ui_service_stopped)
        } else {
            store.text(R.string.priv_ui_binder_died)
        }
        synchronized(store) {
            store.runtimeStartSession?.recordDisconnectedServer()
            store.updateStateAndAppendStartupLog(message) {
                if (it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE) {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        runtimeStartSource = null,
                        runtimeStartProviderId = null,
                        serverInfo = null,
                        adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                        runtimeProgressMessage = null,
                    )
                } else {
                    it.copy(
                        runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                        serverInfo = null,
                        adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                    )
                }
            }
        }
    }

    private fun updateDisconnectedIfIdle(expectedConnectionSerial: Long) {
        if (closed.get()) return
        store.updateState {
            if (
                it.connectionSerial != expectedConnectionSerial ||
                it.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE
            ) {
                it
            } else {
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    runtimeStartSource = null,
                    runtimeStartProviderId = null,
                    serverInfo = null,
                    adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                    runtimeProgressMessage = null,
                )
            }
        }
    }

    private fun publishConnectedServerLocked(serverInfo: PrivilegeServerInfo) {
        val shouldAppendLog = store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING
        val connectionSerial = store.state.value.connectionSerial + 1L
        val connectedMessage = store.text(R.string.priv_ui_connected).takeIf { shouldAppendLog }
        store.updateStateAndAppendStartupLog(connectedMessage) {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = serverInfo,
                adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                runtimeProgressMessage = null,
                connectionSerial = connectionSerial,
            )
        }
        scheduleAdbPermissionRestrictionRefresh(connectionSerial)
    }

    private fun scheduleAdbPermissionRestrictionRefresh(expectedConnectionSerial: Long) {
        val generation = adbRestrictionRefreshGeneration.incrementAndGet()
        coroutineScope.launch(
            Dispatchers.IO + CoroutineName("priv-ui-refresh-adb-restriction"),
        ) {
            val restrictionStatus = runCatching {
                if (isAdbPermissionRestricted()) {
                    PrivilegeUiAdbRestrictionStatus.RESTRICTED
                } else {
                    PrivilegeUiAdbRestrictionStatus.NOT_RESTRICTED
                }
            }.getOrNull() ?: return@launch
            store.updateState { current ->
                if (
                    closed.get() ||
                    adbRestrictionRefreshGeneration.get() != generation ||
                    current.connectionSerial != expectedConnectionSerial ||
                    current.runtimeStatus != PrivilegeUiRuntimeStatus.CONNECTED ||
                    current.serverInfo == null
                ) {
                    current
                } else {
                    current.copy(adbRestrictionStatus = restrictionStatus)
                }
            }
        }
    }
}
