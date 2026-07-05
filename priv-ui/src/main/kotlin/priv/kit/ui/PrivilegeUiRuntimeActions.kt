package priv.kit.ui

import priv.kit.PrivilegeServerInfo
import priv.kit.Privilege
import priv.kit.PrivilegeConfig

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
) : AutoCloseable {
    fun configureOwnerDeathBehavior() {
        PrivilegeConfig.configure(
            followDeathDelayMillis = store.config.followDeathDelayMillis,
            activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
        )
    }

    fun startRoot() {
        runServerStart(rootStartAttempt())
    }

    fun rootStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect =
        PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_starting_root),
            startupSource = store.text(R.string.priv_ui_auth_method_root),
        ) {
            Privilege.startRoot(
                timeoutMillis = store.config.startTimeoutMillis,
                startupLogListener = store.startupLogListener,
            )
        }

    fun stopServer() {
        if (
            store.state.value.busy ||
            store.state.value.runtimeStatus != PrivilegeUiRuntimeStatus.CONNECTED
        ) {
            return
        }
        val message = store.text(R.string.priv_ui_stopping_service)
        store.updateState {
            it.copy(
                busy = true,
                serviceMessage = message,
            )
        }
        store.appendLog(message)
        store.executor.execute {
            store.serverShutdownRequestedByOwner = true
            try {
                Privilege.shutdownServer()
                val stoppedMessage = store.text(R.string.priv_ui_service_stopped)
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        serverInfo = null,
                        serviceMessage = stoppedMessage,
                    )
                }
            } catch (throwable: Throwable) {
                store.updateState {
                    it.copy(
                        busy = false,
                        serviceMessage = throwable.failureMessage(),
                    )
                }
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            } finally {
                store.serverShutdownRequestedByOwner = false
            }
        }
    }

    fun stopCurrentStart() {
        when (store.state.value.runtimeStatus) {
            PrivilegeUiRuntimeStatus.CONNECTED -> stopServer()
            PrivilegeUiRuntimeStatus.STARTING -> stopServerStart()
            PrivilegeUiRuntimeStatus.DISCONNECTED,
            PrivilegeUiRuntimeStatus.FAILED,
            -> Unit
        }
    }

    fun reportNoDirectStart() {
        val message = store.text(R.string.priv_ui_no_direct_start)
        store.updateState { it.copy(serviceMessage = message) }
        store.appendLog(message)
    }

    fun refreshRuntimeStatus() {
        try {
            if (Privilege.pingServer()) {
                connectServer(Privilege.getServerInfo())
            } else {
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        serverInfo = null,
                        serviceMessage = it.serviceMessage.ifBlank {
                            store.text(R.string.priv_ui_ready)
                        },
                    )
                }
            }
        } catch (_: Throwable) {
            store.updateState {
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    serverInfo = null,
                    serviceMessage = store.text(R.string.priv_ui_ready),
                )
            }
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

    fun runServerStart(
        message: String,
        startupSource: String? = null,
        start: () -> PrivilegeServerInfo,
    ) {
        runServerStart(
            PrivilegeUiRuntimeStartAttempt.Connect(
                message = message,
                startupSource = startupSource,
                start = start,
            ),
        )
    }

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect) {
        val generation = beginRuntimeStart(attempt.message) ?: return
        appendStartupSource(attempt.startupSource)
        store.appendStartupLog(attempt.message)
        store.executor.execute {
            if (!markRuntimeStartThread(generation)) return@execute
            try {
                val serverInfo = attempt.start()
                if (isCurrentRuntimeStart(generation)) {
                    connectServer(serverInfo, generation)
                } else {
                    stopServerAfterCancelledStart()
                }
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable, startGeneration = generation)
            } finally {
                clearRuntimeStartThread(generation)
            }
        }
    }

    fun runServerStartRequest(
        message: String,
        startedMessage: String,
        startupSource: String? = null,
        start: () -> Unit,
    ) {
        runServerStartRequest(
            PrivilegeUiRuntimeStartAttempt.Request(
                message = message,
                startedMessage = startedMessage,
                startupSource = startupSource,
                start = start,
            ),
        )
    }

    fun runServerStartRequest(attempt: PrivilegeUiRuntimeStartAttempt.Request) {
        val generation = beginRuntimeStart(attempt.message) ?: return
        appendStartupSource(attempt.startupSource)
        store.appendStartupLog(attempt.message)
        store.executor.execute {
            if (!markRuntimeStartThread(generation)) return@execute
            try {
                attempt.start()
                updateCurrentRuntimeStartState(generation) {
                    it.startRequestSent(attempt.startedMessage)
                }
                if (isCurrentRuntimeStart(generation)) {
                    store.appendStartupLog(attempt.startedMessage)
                }
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable, startGeneration = generation)
            } finally {
                clearRuntimeStartThread(generation)
            }
        }
    }

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>) {
        if (attempts.isEmpty()) {
            reportNoDirectStart()
            return
        }
        val generation = beginRuntimeStart(attempts.first().message) ?: return
        store.executor.execute {
            if (!markRuntimeStartThread(generation)) return@execute
            try {
                var lastFailure: Throwable? = null
                attempts.forEach { attempt ->
                    if (!isCurrentRuntimeStart(generation)) return@execute
                    updateCurrentRuntimeStartState(generation) {
                        it.startingAttempt(attempt.message)
                    }
                    appendStartupSource(attempt.startupSource)
                    store.appendStartupLog(attempt.message)
                    try {
                        when (attempt) {
                            is PrivilegeUiRuntimeStartAttempt.Connect -> {
                                val serverInfo = attempt.start()
                                if (isCurrentRuntimeStart(generation)) {
                                    connectServer(serverInfo, generation)
                                } else {
                                    stopServerAfterCancelledStart()
                                }
                                return@execute
                            }
                            is PrivilegeUiRuntimeStartAttempt.Request -> {
                                attempt.start()
                                updateCurrentRuntimeStartState(generation) {
                                    it.startRequestSent(attempt.startedMessage)
                                }
                                if (isCurrentRuntimeStart(generation)) {
                                    store.appendStartupLog(attempt.startedMessage)
                                }
                                return@execute
                            }
                        }
                    } catch (throwable: Throwable) {
                        if (!isCurrentRuntimeStart(generation)) return@execute
                        lastFailure = throwable
                        store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
                    }
                }
                setRuntimeFailure(
                    lastFailure ?: IllegalStateException(store.text(R.string.priv_ui_no_direct_start)),
                    appendDiagnostic = false,
                    startGeneration = generation,
                )
            } finally {
                clearRuntimeStartThread(generation)
            }
        }
    }

    fun <T> runBusy(
        message: String,
        action: () -> T,
        onFailure: ((Throwable) -> Unit)? = null,
        onSuccess: (T) -> String,
    ) {
        if (store.state.value.busy) return
        store.updateState {
            it.copy(
                busy = true,
                serviceMessage = message,
            )
        }
        store.appendLog(message)
        store.executor.execute {
            try {
                val result = action()
                val resultMessage = onSuccess(result)
                store.updateState {
                    it.copy(
                        busy = false,
                        serviceMessage = store.idleMessage(it),
                    )
                }
                store.appendLog(resultMessage)
            } catch (throwable: Throwable) {
                onFailure?.invoke(throwable)
                store.updateState {
                    it.copy(
                        busy = false,
                        serviceMessage = throwable.failureMessage(),
                    )
                }
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            }
        }
    }

    override fun close() {
        store.serverConnectedListener?.close()
        store.serverDisconnectedWatcher?.close()
        store.serverConnectedListener = null
        store.serverDisconnectedWatcher = null
    }

    private fun appendStartupSource(startupSource: String?) {
        val source = startupSource?.trim()?.takeIf { it.isNotEmpty() } ?: return
        store.appendStartupLog(store.text(R.string.priv_ui_startup_source, source))
    }

    private fun beginRuntimeStart(message: String): Long? {
        if (store.state.value.busy) return null
        val generation = store.runtimeStartGeneration.incrementAndGet()
        store.clearStartupLog()
        updateCurrentRuntimeStartState(generation) {
            it.startingAttempt(message)
        }
        return generation
    }

    private fun stopServerStart() {
        if (store.state.value.runtimeStatus != PrivilegeUiRuntimeStatus.STARTING) return
        val message = store.text(R.string.priv_ui_startup_stopped)
        val thread = synchronized(store) {
            store.runtimeStartGeneration.incrementAndGet()
            store.runtimeStartThread.also {
                store.runtimeStartThread = null
            }
        }
        thread?.interrupt()
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                serverInfo = null,
                serviceMessage = message,
            )
        }
        store.appendStartupLog(message)
    }

    private fun markRuntimeStartThread(generation: Long): Boolean =
        synchronized(store) {
            if (!isCurrentRuntimeStart(generation)) {
                false
            } else {
                store.runtimeStartThread = Thread.currentThread()
                true
            }
        }

    private fun clearRuntimeStartThread(generation: Long) {
        synchronized(store) {
            if (
                isCurrentRuntimeStart(generation) &&
                store.runtimeStartThread === Thread.currentThread()
            ) {
                store.runtimeStartThread = null
            }
        }
    }

    private fun updateCurrentRuntimeStartState(
        generation: Long,
        transform: (PrivilegeUiState) -> PrivilegeUiState,
    ) {
        store.updateState {
            if (isCurrentRuntimeStart(generation)) transform(it) else it
        }
    }

    private fun isCurrentRuntimeStart(generation: Long): Boolean =
        store.runtimeStartGeneration.get() == generation

    private fun stopServerAfterCancelledStart() {
        store.serverShutdownRequestedByOwner = true
        try {
            runCatching {
                Privilege.shutdownServer()
            }.onFailure { throwable ->
                store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
            }
        } finally {
            store.serverShutdownRequestedByOwner = false
        }
    }

    private fun connectServer(
        serverInfo: PrivilegeServerInfo,
        startGeneration: Long? = null,
    ) {
        if (startGeneration != null && !isCurrentRuntimeStart(startGeneration)) {
            stopServerAfterCancelledStart()
            return
        }
        val shouldAppendLog = store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING
        var connected = false
        store.updateState {
            if (startGeneration != null && !isCurrentRuntimeStart(startGeneration)) {
                it
            } else {
                connected = true
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                    serverInfo = serverInfo,
                    serviceMessage = store.text(R.string.priv_ui_connected),
                    connectionSerial = it.connectionSerial + 1L,
                )
            }
        }
        if (!connected) {
            stopServerAfterCancelledStart()
            return
        }
        if (shouldAppendLog && (startGeneration == null || isCurrentRuntimeStart(startGeneration))) {
            store.appendStartupLog(store.text(R.string.priv_ui_connected))
        }
    }

    private fun setRuntimeFailure(
        throwable: Throwable,
        appendDiagnostic: Boolean = true,
        startGeneration: Long? = null,
    ) {
        if (startGeneration != null && !isCurrentRuntimeStart(startGeneration)) return
        store.updateState {
            if (startGeneration != null && !isCurrentRuntimeStart(startGeneration)) {
                it
            } else {
                it.startFailed(throwable.failureMessage())
            }
        }
        if (appendDiagnostic && (startGeneration == null || isCurrentRuntimeStart(startGeneration))) {
            store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }

    private fun handleServerDisconnected() {
        val message = if (store.serverShutdownRequestedByOwner) {
            store.text(R.string.priv_ui_service_stopped)
        } else {
            store.text(R.string.priv_ui_binder_died)
        }
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                serverInfo = null,
                serviceMessage = message,
            )
        }
        store.appendLog(message)
    }

    private fun PrivilegeUiState.startingAttempt(message: String): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = true,
                serviceMessage = message,
            )
        } else {
            copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serverInfo = null,
                serviceMessage = message,
            )
        }

    private fun PrivilegeUiState.startRequestSent(message: String): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = false,
                serviceMessage = message,
            )
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serviceMessage = message,
            )
        }

    private fun PrivilegeUiState.startFailed(message: String): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = false,
                serviceMessage = message,
            )
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
                serverInfo = null,
                serviceMessage = message,
            )
        }
}

internal sealed interface PrivilegeUiRuntimeStartAttempt {
    val message: String
    val startupSource: String?

    class Connect(
        override val message: String,
        override val startupSource: String?,
        val start: () -> PrivilegeServerInfo,
    ) : PrivilegeUiRuntimeStartAttempt

    class Request(
        override val message: String,
        val startedMessage: String,
        override val startupSource: String?,
        val start: () -> Unit,
    ) : PrivilegeUiRuntimeStartAttempt
}
