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
            PrivilegeUiRuntimeStatus.STARTING -> interruptServerStart()
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
            ) {
                start()
            },
        )
    }

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect) {
        val session = beginRuntimeStart(attempt.message) ?: return
        appendStartupSource(attempt.startupSource)
        store.appendStartupLog(attempt.message)
        launchRuntimeStart(session, "priv-ui-runtime-start") {
            try {
                val serverInfo = runInterruptible {
                    attempt.start(session)
                }
                if (isCurrentRuntimeStart(session)) {
                    connectServer(serverInfo, session)
                } else {
                    stopServerAfterCancelledStart()
                }
            } catch (_: CancellationException) {
                return@launchRuntimeStart
            } catch (throwable: Throwable) {
                if (attempt.onFailure?.invoke(throwable) != true) {
                    setRuntimeFailure(throwable, startSession = session)
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
            ) {
                start()
            },
        )
    }

    fun runServerStartRequest(attempt: PrivilegeUiRuntimeStartAttempt.Request) {
        val session = beginRuntimeStart(attempt.message) ?: return
        appendStartupSource(attempt.startupSource)
        store.appendStartupLog(attempt.message)
        launchRuntimeStart(session, "priv-ui-runtime-start-request") {
            try {
                runInterruptible {
                    attempt.start(session)
                }
                updateCurrentRuntimeStartState(session) {
                    it.startRequestSent(attempt.startedMessage)
                }
                if (isCurrentRuntimeStart(session)) {
                    store.appendStartupLog(attempt.startedMessage)
                }
            } catch (_: CancellationException) {
                return@launchRuntimeStart
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable, startSession = session)
            }
        }
    }

    fun runServerStartWorkflow(attempt: PrivilegeUiRuntimeStartAttempt.Workflow) {
        val session = beginRuntimeStart(attempt.message) ?: return
        appendStartupSource(attempt.startupSource)
        store.appendStartupLog(attempt.message)
        launchRuntimeStart(session, "priv-ui-runtime-start-workflow") {
            try {
                when (val result = attempt.start(session)) {
                    is PrivilegeUiRuntimeStartResult.Connected -> {
                        if (isCurrentRuntimeStart(session)) {
                            connectServer(result.serverInfo, session)
                        } else {
                            stopServerAfterCancelledStart()
                        }
                    }
                    is PrivilegeUiRuntimeStartResult.RequestSent -> {
                        updateCurrentRuntimeStartState(session) {
                            it.startRequestSent(result.message)
                        }
                        if (isCurrentRuntimeStart(session)) {
                            store.appendStartupLog(result.message)
                        }
                    }
                    PrivilegeUiRuntimeStartResult.Finished -> {
                        finishRuntimeStartWithoutResult(session)
                    }
                }
            } catch (_: CancellationException) {
                return@launchRuntimeStart
            } catch (throwable: Throwable) {
                if (attempt.onFailure?.invoke(throwable) != true) {
                    setRuntimeFailure(throwable, startSession = session)
                }
            }
        }
    }

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>) {
        if (attempts.isEmpty()) {
            reportNoDirectStart()
            return
        }
        val session = beginRuntimeStart(attempts.first().message) ?: return
        launchRuntimeStart(session, "priv-ui-runtime-start-fallback") {
            var lastFailure: Throwable? = null
            attempts.forEach { attempt ->
                if (!isCurrentRuntimeStart(session)) return@launchRuntimeStart
                updateCurrentRuntimeStartState(session) {
                    it.startingAttempt(attempt.message)
                }
                appendStartupSource(attempt.startupSource)
                store.appendStartupLog(attempt.message)
                try {
                    when (attempt) {
                        is PrivilegeUiRuntimeStartAttempt.Connect -> {
                            val serverInfo = runInterruptible {
                                attempt.start(session)
                            }
                            if (isCurrentRuntimeStart(session)) {
                                connectServer(serverInfo, session)
                            } else {
                                stopServerAfterCancelledStart()
                            }
                            return@launchRuntimeStart
                        }
                        is PrivilegeUiRuntimeStartAttempt.Request -> {
                            runInterruptible {
                                attempt.start(session)
                            }
                            updateCurrentRuntimeStartState(session) {
                                it.startRequestSent(attempt.startedMessage)
                            }
                            if (isCurrentRuntimeStart(session)) {
                                store.appendStartupLog(attempt.startedMessage)
                            }
                            return@launchRuntimeStart
                        }
                        is PrivilegeUiRuntimeStartAttempt.Workflow -> {
                            when (val result = attempt.start(session)) {
                                is PrivilegeUiRuntimeStartResult.Connected -> {
                                    if (isCurrentRuntimeStart(session)) {
                                        connectServer(result.serverInfo, session)
                                    } else {
                                        stopServerAfterCancelledStart()
                                    }
                                    return@launchRuntimeStart
                                }
                                is PrivilegeUiRuntimeStartResult.RequestSent -> {
                                    updateCurrentRuntimeStartState(session) {
                                        it.startRequestSent(result.message)
                                    }
                                    if (isCurrentRuntimeStart(session)) {
                                        store.appendStartupLog(result.message)
                                    }
                                    return@launchRuntimeStart
                                }
                                PrivilegeUiRuntimeStartResult.Finished -> Unit
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    return@launchRuntimeStart
                } catch (throwable: Throwable) {
                    if (!isCurrentRuntimeStart(session)) return@launchRuntimeStart
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
                startSession = session,
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
        val session = store.runtimeStartSession
        store.runtimeStartGeneration.incrementAndGet()
        store.runtimeStartSession = null
        store.runtimeStartJob?.cancel()
        store.runtimeStartJob = null
        session?.close()
        store.serverConnectedListener?.close()
        store.serverDisconnectedWatcher?.close()
        store.serverConnectedListener = null
        store.serverDisconnectedWatcher = null
    }

    private fun appendStartupSource(startupSource: String?) {
        val source = startupSource?.trim()?.takeIf { it.isNotEmpty() } ?: return
        store.appendStartupLog(store.text(R.string.priv_ui_startup_source, source))
    }

    private fun beginRuntimeStart(message: String): PrivilegeUiRuntimeStartSession? {
        if (store.state.value.busy) return null
        val generation = store.runtimeStartGeneration.incrementAndGet()
        val session = PrivilegeUiRuntimeStartSession(generation)
        store.clearStartupLog()
        synchronized(store) {
            store.runtimeStartSession?.close()
            store.runtimeStartSession = session
        }
        updateCurrentRuntimeStartState(session) {
            it.startingAttempt(message)
        }
        return session
    }

    private fun interruptServerStart() {
        if (store.state.value.runtimeStatus != PrivilegeUiRuntimeStatus.STARTING) return
        val message = store.text(R.string.priv_ui_startup_interrupted)
        val sessionAndJob = synchronized(store) {
            store.runtimeStartGeneration.incrementAndGet()
            val session = store.runtimeStartSession
            val job = store.runtimeStartJob
            store.runtimeStartSession = null
            store.runtimeStartJob = null
            session to job
        }
        sessionAndJob.first?.close()
        sessionAndJob.second?.cancel()
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
        session: PrivilegeUiRuntimeStartSession,
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
                clearRuntimeStartJob(session, job)
            }
        }
        if (markRuntimeStartJob(session, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun markRuntimeStartJob(
        session: PrivilegeUiRuntimeStartSession,
        job: Job,
    ): Boolean =
        synchronized(store) {
            if (!isCurrentRuntimeStart(session)) {
                false
            } else {
                store.runtimeStartJob = job
                true
            }
        }

    private fun clearRuntimeStartJob(
        session: PrivilegeUiRuntimeStartSession,
        job: Job,
    ) {
        synchronized(store) {
            if (
                isCurrentRuntimeStart(session) &&
                store.runtimeStartJob === job
            ) {
                store.runtimeStartJob = null
                store.runtimeStartSession
                    ?.takeIf { it === session }
                    ?.close()
                store.runtimeStartSession = null
            }
        }
    }

    private fun updateCurrentRuntimeStartState(
        session: PrivilegeUiRuntimeStartSession,
        transform: (PrivilegeUiState) -> PrivilegeUiState,
    ) {
        store.updateState {
            if (isCurrentRuntimeStart(session)) transform(it) else it
        }
    }

    private fun isCurrentRuntimeStart(session: PrivilegeUiRuntimeStartSession): Boolean =
        store.runtimeStartGeneration.get() == session.generation && session.active

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
        startSession: PrivilegeUiRuntimeStartSession? = null,
    ) {
        if (startSession != null && !isCurrentRuntimeStart(startSession)) {
            stopServerAfterCancelledStart()
            return
        }
        val shouldAppendLog = store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING
        var connected = false
        store.updateState {
            if (startSession != null && !isCurrentRuntimeStart(startSession)) {
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
        if (shouldAppendLog && (startSession == null || isCurrentRuntimeStart(startSession))) {
            store.appendStartupLog(store.text(R.string.priv_ui_connected))
        }
    }

    private fun setRuntimeFailure(
        throwable: Throwable,
        appendDiagnostic: Boolean = true,
        startSession: PrivilegeUiRuntimeStartSession? = null,
    ) {
        if (startSession != null && !isCurrentRuntimeStart(startSession)) return
        val failureMessage = throwable.failureMessage()
        store.updateState {
            if (startSession != null && !isCurrentRuntimeStart(startSession)) {
                it
            } else {
                it.startFailed()
            }
        }
        store.showFailure(failureMessage)
        if (appendDiagnostic && (startSession == null || isCurrentRuntimeStart(startSession))) {
            store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }

    private fun finishRuntimeStartWithoutResult(session: PrivilegeUiRuntimeStartSession) {
        updateCurrentRuntimeStartState(session) {
            if (it.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
                it.copy(
                    busy = false,
                    runtimeProgressMessage = null,
                )
            } else {
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    serverInfo = null,
                    runtimeProgressMessage = null,
                )
            }
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
        val start: PrivilegeUiRuntimeStartSession.() -> PrivilegeServerInfo,
    ) : PrivilegeUiRuntimeStartAttempt

    class Request(
        override val message: String,
        val startedMessage: String,
        override val startupSource: String?,
        val start: PrivilegeUiRuntimeStartSession.() -> Unit,
    ) : PrivilegeUiRuntimeStartAttempt

    class Workflow(
        override val message: String,
        override val startupSource: String?,
        val onFailure: ((Throwable) -> Boolean)? = null,
        val start: suspend PrivilegeUiRuntimeStartSession.() -> PrivilegeUiRuntimeStartResult,
    ) : PrivilegeUiRuntimeStartAttempt
}

internal sealed interface PrivilegeUiRuntimeStartResult {
    class Connected(val serverInfo: PrivilegeServerInfo) : PrivilegeUiRuntimeStartResult
    class RequestSent(val message: String) : PrivilegeUiRuntimeStartResult
    data object Finished : PrivilegeUiRuntimeStartResult
}
