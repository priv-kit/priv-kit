package priv.kit.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import priv.kit.PrivilegeServerInfo
import priv.kit.Privilege
import priv.kit.PrivilegeConfig

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
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
            )
        }
        store.appendLog(message)
        coroutineScope.launch(Dispatchers.IO + CoroutineName("priv-ui-stop-server")) {
            store.serverShutdownRequestedByOwner = true
            try {
                runInterruptible {
                    Privilege.shutdownServer()
                }
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        serverInfo = null,
                        runtimeProgressMessage = null,
                    )
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (throwable: Throwable) {
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeProgressMessage = null,
                    )
                }
                store.showFailure(throwable)
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
        store.showFailure(message)
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
                        runtimeProgressMessage = null,
                    )
                }
            }
        } catch (_: Throwable) {
            store.updateState {
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    serverInfo = null,
                    runtimeProgressMessage = null,
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
        launchRuntimeStart(generation, "priv-ui-runtime-start") {
            try {
                val serverInfo = runInterruptible {
                    attempt.start()
                }
                if (isCurrentRuntimeStart(generation)) {
                    connectServer(serverInfo, generation)
                } else {
                    stopServerAfterCancelledStart()
                }
            } catch (_: CancellationException) {
                return@launchRuntimeStart
            } catch (throwable: Throwable) {
                if (attempt.onFailure?.invoke(throwable) != true) {
                    setRuntimeFailure(throwable, startGeneration = generation)
                }
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
        launchRuntimeStart(generation, "priv-ui-runtime-start-request") {
            try {
                runInterruptible {
                    attempt.start()
                }
                updateCurrentRuntimeStartState(generation) {
                    it.startRequestSent(attempt.startedMessage)
                }
                if (isCurrentRuntimeStart(generation)) {
                    store.appendStartupLog(attempt.startedMessage)
                }
            } catch (_: CancellationException) {
                return@launchRuntimeStart
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable, startGeneration = generation)
            }
        }
    }

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>) {
        if (attempts.isEmpty()) {
            reportNoDirectStart()
            return
        }
        val generation = beginRuntimeStart(attempts.first().message) ?: return
        launchRuntimeStart(generation, "priv-ui-runtime-start-fallback") {
            var lastFailure: Throwable? = null
            attempts.forEach { attempt ->
                if (!isCurrentRuntimeStart(generation)) return@launchRuntimeStart
                updateCurrentRuntimeStartState(generation) {
                    it.startingAttempt(attempt.message)
                }
                appendStartupSource(attempt.startupSource)
                store.appendStartupLog(attempt.message)
                try {
                    when (attempt) {
                        is PrivilegeUiRuntimeStartAttempt.Connect -> {
                            val serverInfo = runInterruptible {
                                attempt.start()
                            }
                            if (isCurrentRuntimeStart(generation)) {
                                connectServer(serverInfo, generation)
                            } else {
                                stopServerAfterCancelledStart()
                            }
                            return@launchRuntimeStart
                        }
                        is PrivilegeUiRuntimeStartAttempt.Request -> {
                            runInterruptible {
                                attempt.start()
                            }
                            updateCurrentRuntimeStartState(generation) {
                                it.startRequestSent(attempt.startedMessage)
                            }
                            if (isCurrentRuntimeStart(generation)) {
                                store.appendStartupLog(attempt.startedMessage)
                            }
                            return@launchRuntimeStart
                        }
                    }
                } catch (_: CancellationException) {
                    return@launchRuntimeStart
                } catch (throwable: Throwable) {
                    if (!isCurrentRuntimeStart(generation)) return@launchRuntimeStart
                    if (
                        attempt is PrivilegeUiRuntimeStartAttempt.Connect &&
                        attempt.onFailure?.invoke(throwable) == true
                    ) {
                        return@launchRuntimeStart
                    }
                    lastFailure = throwable
                    store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
                }
            }
            setRuntimeFailure(
                lastFailure ?: IllegalStateException(store.text(R.string.priv_ui_no_direct_start)),
                appendDiagnostic = false,
                startGeneration = generation,
            )
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
            )
        }
        store.appendLog(message)
        coroutineScope.launch(Dispatchers.IO + CoroutineName("priv-ui-runtime-busy")) {
            try {
                val result = runInterruptible {
                    action()
                }
                val resultMessage = onSuccess(result)
                store.updateState {
                    it.copy(
                        busy = false,
                    )
                }
                store.appendLog(resultMessage)
            } catch (_: CancellationException) {
                return@launch
            } catch (throwable: Throwable) {
                onFailure?.invoke(throwable)
                store.updateState {
                    it.copy(
                        busy = false,
                    )
                }
                store.showFailure(throwable)
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
        val job = synchronized(store) {
            store.runtimeStartGeneration.incrementAndGet()
            store.runtimeStartJob.also {
                store.runtimeStartJob = null
            }
        }
        job?.cancel()
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                serverInfo = null,
                runtimeProgressMessage = null,
            )
        }
        store.appendStartupLog(message)
    }

    private fun launchRuntimeStart(
        generation: Long,
        name: String,
        block: suspend () -> Unit,
    ) {
        lateinit var job: Job
        job = coroutineScope.launch(
            context = Dispatchers.IO + CoroutineName(name),
            start = CoroutineStart.LAZY,
        ) {
            try {
                block()
            } finally {
                clearRuntimeStartJob(generation, job)
            }
        }
        if (markRuntimeStartJob(generation, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun markRuntimeStartJob(
        generation: Long,
        job: Job,
    ): Boolean =
        synchronized(store) {
            if (!isCurrentRuntimeStart(generation)) {
                false
            } else {
                store.runtimeStartJob = job
                true
            }
        }

    private fun clearRuntimeStartJob(
        generation: Long,
        job: Job,
    ) {
        synchronized(store) {
            if (
                isCurrentRuntimeStart(generation) &&
                store.runtimeStartJob === job
            ) {
                store.runtimeStartJob = null
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
                    runtimeProgressMessage = null,
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
        val failureMessage = throwable.failureMessage()
        store.updateState {
            if (startGeneration != null && !isCurrentRuntimeStart(startGeneration)) {
                it
            } else {
                it.startFailed()
            }
        }
        store.showFailure(failureMessage)
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
                runtimeProgressMessage = null,
            )
        }
        store.appendLog(message)
    }

    private fun PrivilegeUiState.startingAttempt(message: String): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = true,
                runtimeProgressMessage = message,
            )
        } else {
            copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serverInfo = null,
                runtimeProgressMessage = message,
            )
        }

    private fun PrivilegeUiState.startRequestSent(message: String): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = false,
                runtimeProgressMessage = message,
            )
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                runtimeProgressMessage = message,
            )
        }

    private fun PrivilegeUiState.startFailed(): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = false,
                runtimeProgressMessage = null,
            )
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
                serverInfo = null,
                runtimeProgressMessage = null,
            )
        }
}

internal sealed interface PrivilegeUiRuntimeStartAttempt {
    val message: String
    val startupSource: String?

    class Connect(
        override val message: String,
        override val startupSource: String?,
        val onFailure: ((Throwable) -> Boolean)? = null,
        val start: () -> PrivilegeServerInfo,
    ) : PrivilegeUiRuntimeStartAttempt

    class Request(
        override val message: String,
        val startedMessage: String,
        override val startupSource: String?,
        val start: () -> Unit,
    ) : PrivilegeUiRuntimeStartAttempt
}
