package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.Privilege
import priv.kit.PrivilegeConfig
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeServerLaunchUncertainException
import priv.kit.PrivilegeStartupLogLine

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val shutdownServer: () -> Unit = { Privilege.shutdownServer() },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val runtimeStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextStopOperationId = 0L
    private val activeStopOperationIds = mutableSetOf<Long>()

    internal val isClosed: Boolean
        get() = closed.get()

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
                    store.showFailure(throwable)
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
        if (store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.RUNNING) {
            requestRuntimeStartCancellation()
        }
    }

    private fun reportStartFailedIfIdle() {
        synchronized(store) {
            val current = store.state.value
            if (
                closed.get() ||
                current.busy ||
                current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
                current.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
            ) {
                return
            }
            val message = store.text(R.string.priv_ui_start_failed)
            store.showFailure(message)
            store.appendLog(message)
        }
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
        runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
        start: () -> PrivilegeServerInfo,
    ) {
        runServerStart(
            PrivilegeUiRuntimeStartAttempt.Connect(
                message = message,
                startupSource = startupSource,
                runtimeStartSource = runtimeStartSource,
            ) {
                start()
            },
        )
    }

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect) {
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start")
    }

    fun runServerStartRequest(
        message: String,
        startedMessage: String,
        startupSource: String? = null,
        runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
        start: () -> Unit,
    ) {
        runServerStartRequest(
            PrivilegeUiRuntimeStartAttempt.Request(
                message = message,
                startedMessage = startedMessage,
                startupSource = startupSource,
                runtimeStartSource = runtimeStartSource,
            ) {
                start()
            },
        )
    }

    fun runServerStartRequest(attempt: PrivilegeUiRuntimeStartAttempt.Request) {
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start-request")
    }

    fun runServerStartWorkflow(attempt: PrivilegeUiRuntimeStartAttempt.Workflow) {
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start-workflow")
    }

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>) {
        if (attempts.isEmpty()) {
            reportStartFailedIfIdle()
            return
        }
        val firstAttempt = attempts.first()
        launchRuntimeStart(
            initialAttempt = firstAttempt,
            showAttemptFeedback = false,
            name = "priv-ui-runtime-start-fallback",
            beforeStart = { store.clearStartupLog() },
        ) { session ->
            runFallbackAttempts(session, attempts)
        }
    }

    fun <T> runBusy(
        message: String,
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
                    store.showFailure(throwable)
                    store.appendLog(throwable.toPrivilegeUiDiagnosticString())
                }
            }
        }
    }

    override fun close() {
        if (!synchronized(store) { closed.compareAndSet(false, true) }) return
        val hadRuntimeStart = requestRuntimeStartCancellation(ownerClosing = true)
        runtimeStartScope.cancel()
        if (!hadRuntimeStart) cleanupScope.cancel()
        runCatching { store.serverConnectedListener?.close() }
        runCatching { store.serverDisconnectedWatcher?.close() }
        store.serverConnectedListener = null
        store.serverDisconnectedWatcher = null
    }

    private fun runServerStartAttempt(
        attempt: PrivilegeUiRuntimeStartAttempt,
        name: String,
    ) {
        launchRuntimeStart(
            initialAttempt = attempt,
            name = name,
            beforeStart = { session ->
                store.clearStartupLog()
                appendStartupSource(session, attempt.startupSource)
                session.appendStartupLog(attempt.message)
            },
        ) { session ->
            runSingleAttempt(session, attempt)
        }
    }

    private fun launchRuntimeStart(
        initialAttempt: PrivilegeUiRuntimeStartAttempt,
        showAttemptFeedback: Boolean = true,
        name: String,
        beforeStart: (PrivilegeUiRuntimeStartSession) -> Unit,
        block: suspend (PrivilegeUiRuntimeStartSession) -> RuntimeStartCompletion,
    ): Boolean = synchronized(store) {
        val current = store.state.value
        if (
            closed.get() ||
            current.busy ||
            current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.runtimeStartSession != null ||
            store.runtimeStartJob != null
        ) {
            return@synchronized false
        }

        val session = PrivilegeUiRuntimeStartSession(
            generation = store.runtimeStartGeneration.incrementAndGet(),
            showAttemptFeedback = showAttemptFeedback,
            startupLogSink = ::appendSessionStartupLog,
            structuredStartupLogSink = ::appendSessionStartupLog,
        )
        lateinit var job: Job
        job = runtimeStartScope.launch(
            context = CoroutineName(name),
            start = CoroutineStart.LAZY,
        ) {
            val completion = try {
                block(session)
            } catch (_: CancellationException) {
                RuntimeStartCompletion.Cancelled
            } catch (throwable: Throwable) {
                RuntimeStartCompletion.Failure(throwable)
            }
            completeRuntimeStart(session, completion)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                val completion = if (
                    cause is CancellationException ||
                    session.cancellationRequested
                ) {
                    RuntimeStartCompletion.Cancelled
                } else {
                    RuntimeStartCompletion.Failure(cause)
                }
                completeRuntimeStart(session, completion)
            }
        }

        store.runtimeStartSession = session
        store.runtimeStartJob = job
        store.updateState {
            it.startingAttempt(
                attempt = initialAttempt,
                phase = PrivilegeUiRuntimeStartPhase.RUNNING,
            )
        }
        beforeStart(session)
        job.start()
        true
    }

    private suspend fun runSingleAttempt(
        session: PrivilegeUiRuntimeStartSession,
        attempt: PrivilegeUiRuntimeStartAttempt,
    ): RuntimeStartCompletion {
        return try {
            session.checkActive()
            when (val result = executeRuntimeStartAttempt(session, attempt)) {
                is PrivilegeUiRuntimeStartResult.Connected ->
                    RuntimeStartCompletion.Connected(result.serverInfo)
                is PrivilegeUiRuntimeStartResult.RequestSent ->
                    awaitExternalStartCompletion(session, result.message)
                PrivilegeUiRuntimeStartResult.Finished -> RuntimeStartCompletion.Finished
            }
        } catch (_: CancellationException) {
            RuntimeStartCompletion.Cancelled
        } catch (throwable: Throwable) {
            if (session.cancellationRequested) {
                RuntimeStartCompletion.Cancelled
            } else {
                attempt.startFailureDisposition(throwable)
                    ?.let(RuntimeStartCompletion::HandledFailure)
                    ?: RuntimeStartCompletion.Failure(throwable)
            }
        }
    }

    private suspend fun runFallbackAttempts(
        session: PrivilegeUiRuntimeStartSession,
        attempts: List<PrivilegeUiRuntimeStartAttempt>,
    ): RuntimeStartCompletion {
        attempts.forEach { attempt ->
            try {
                session.checkActive()
                if (!isCurrentRuntimeStart(session)) return RuntimeStartCompletion.Superseded
                updateCurrentRuntimeStartAttempt(session, attempt)
                appendStartupSource(session, attempt.startupSource)
                session.appendStartupLog(attempt.message)
                when (val result = executeRuntimeStartAttempt(session, attempt)) {
                    is PrivilegeUiRuntimeStartResult.Connected ->
                        return RuntimeStartCompletion.Connected(result.serverInfo)
                    is PrivilegeUiRuntimeStartResult.RequestSent -> {
                        return awaitExternalStartCompletion(session, result.message)
                    }
                    PrivilegeUiRuntimeStartResult.Finished -> Unit
                }
            } catch (_: CancellationException) {
                return RuntimeStartCompletion.Cancelled
            } catch (throwable: Throwable) {
                if (session.cancellationRequested) return RuntimeStartCompletion.Cancelled
                if (!isCurrentRuntimeStart(session)) return RuntimeStartCompletion.Superseded
                attempt.startFailureDisposition(throwable)?.let { disposition ->
                    applySilentFallbackFailureDisposition(session, disposition)
                }
                session.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
                if (
                    attempt is PrivilegeUiRuntimeStartAttempt.Request ||
                    throwable is PrivilegeServerLaunchUncertainException
                ) {
                    return RuntimeStartCompletion.Failure(
                        throwable = IllegalStateException(store.text(R.string.priv_ui_start_failed)),
                        appendDiagnostic = false,
                    )
                }
            }
        }
        return RuntimeStartCompletion.Failure(
            throwable = IllegalStateException(store.text(R.string.priv_ui_start_failed)),
            appendDiagnostic = false,
        )
    }

    private fun applySilentFallbackFailureDisposition(
        session: PrivilegeUiRuntimeStartSession,
        disposition: PrivilegeUiRuntimeStartFailureDisposition,
    ) {
        var afterCommit: (() -> Unit)? = null
        synchronized(store) {
            if (
                closed.get() ||
                !isCurrentRuntimeStartLocked(session) ||
                session.cancellationRequested
            ) {
                return
            }
            store.updateState(disposition.stateTransform)
            afterCommit = disposition.afterCommit
        }
        afterCommit?.let { callback -> runCatching(callback) }
    }

    private suspend fun executeRuntimeStartAttempt(
        session: PrivilegeUiRuntimeStartSession,
        attempt: PrivilegeUiRuntimeStartAttempt,
    ): PrivilegeUiRuntimeStartResult =
        when (attempt) {
            is PrivilegeUiRuntimeStartAttempt.Connect -> {
                val serverInfo = runInterruptible { attempt.start(session) }
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
            is PrivilegeUiRuntimeStartAttempt.Request -> {
                // External providers expose no cancellation hook. This call intentionally has
                // zero coroutine cancellation points and is allowed to return on its own.
                attempt.start(session)
                PrivilegeUiRuntimeStartResult.RequestSent(attempt.startedMessage)
            }
            is PrivilegeUiRuntimeStartAttempt.Workflow -> attempt.start(session)
        }

    private suspend fun awaitExternalStartCompletion(
        session: PrivilegeUiRuntimeStartSession,
        message: String,
    ): RuntimeStartCompletion {
        updateCurrentRuntimeStartState(session) {
            if (it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.RUNNING) {
                it.copy(runtimeProgressMessage = message)
            } else {
                it
            }
        }
        if (isCurrentRuntimeStart(session) && !closed.get()) {
            session.appendStartupLog(message)
        }

        withContext(NonCancellable) {
            withTimeoutOrNull(store.config.startTimeoutMillis) {
                session.awaitCompletionSignal()
            }
        }

        if (!isCurrentRuntimeStart(session)) return RuntimeStartCompletion.Superseded
        if (session.cancellationRequested) return RuntimeStartCompletion.Cancelled
        return RuntimeStartCompletion.Failure(
            throwable = IllegalStateException(store.text(R.string.priv_ui_start_failed)),
            appendDiagnostic = false,
        )
    }

    private fun requestRuntimeStartCancellation(ownerClosing: Boolean = false): Boolean {
        lateinit var session: PrivilegeUiRuntimeStartSession
        var job: Job? = null
        var firstRequest = false
        synchronized(store) {
            session = store.runtimeStartSession ?: return false
            if (!ownerClosing && store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.RUNNING) {
                return false
            }
            firstRequest = session.markCancellationRequested()
            if (!ownerClosing && !firstRequest) return false
            job = store.runtimeStartJob
            if (ownerClosing) {
                if (store.runtimeStartSession === session) {
                    store.runtimeStartGeneration.incrementAndGet()
                    store.runtimeStartSession = null
                    store.runtimeStartJob = null
                }
            } else {
                store.updateState {
                    it.copy(
                        busy = true,
                        runtimeStartPhase = PrivilegeUiRuntimeStartPhase.CANCELLING,
                        runtimeProgressMessage = store.text(R.string.priv_ui_startup_cancelling),
                    )
                }
            }
        }

        job?.cancel(CancellationException("Runtime start was cancelled"))
        session.signalCompletion()
        if (firstRequest || ownerClosing) {
            cleanupScope.launch(CoroutineName("priv-ui-runtime-start-cleanup")) {
                try {
                    session.closeCancellationResources { throwable ->
                        appendCleanupFailure(session, throwable)
                    }
                    if (ownerClosing) {
                        session.finish { throwable ->
                            appendCleanupFailure(session, throwable)
                        }
                    }
                } finally {
                    if (ownerClosing) cleanupScope.cancel()
                }
            }
        }
        return true
    }

    private fun completeRuntimeStart(
        session: PrivilegeUiRuntimeStartSession,
        completion: RuntimeStartCompletion,
    ) {
        if (completion is RuntimeStartCompletion.Connected) {
            connectServer(
                serverInfo = completion.serverInfo,
                expectedSession = session,
            )
            return
        }

        synchronized(store) {
            if (!isCurrentRuntimeStartLocked(session)) return
        }
        session.finish { throwable ->
            appendCleanupFailure(session, throwable)
        }

        var resolvedCompletion: RuntimeStartCompletion = completion
        synchronized(store) {
            if (!ownsRuntimeStartLocked(session)) return
            if (session.cancellationRequested) {
                resolvedCompletion = RuntimeStartCompletion.Cancelled
            }
        }
        var afterCommit: (() -> Unit)? = null
        var userAction: (() -> Unit)? = null
        var committedGeneration: Long? = null
        var committedConnectionSerial: Long? = null
        synchronized(store) {
            if (!ownsRuntimeStartLocked(session)) return
            if (session.connectionClaimed) return
            if (session.cancellationRequested) {
                resolvedCompletion = RuntimeStartCompletion.Cancelled
            }
            store.runtimeStartGeneration.incrementAndGet()
            store.runtimeStartSession = null
            store.runtimeStartJob = null
            if (!closed.get()) {
                when (val resolved = resolvedCompletion) {
                    RuntimeStartCompletion.Cancelled -> {
                        store.appendStartupLog(store.text(R.string.priv_ui_startup_interrupted))
                    }
                    is RuntimeStartCompletion.Failure -> {
                        store.showFailure(resolved.throwable.failureMessage())
                        if (resolved.appendDiagnostic) {
                            store.appendStartupLog(resolved.throwable.toPrivilegeUiDiagnosticString())
                        }
                    }
                    RuntimeStartCompletion.Finished,
                    RuntimeStartCompletion.Superseded,
                    is RuntimeStartCompletion.Connected,
                    -> Unit
                    is RuntimeStartCompletion.HandledFailure -> {
                        resolved.disposition.snackbarMessage?.let(store::showSnackbar)
                        resolved.disposition.startupLogLines.forEach(store::appendStartupLog)
                    }
                }
            }
            store.updateState { current ->
                when (val resolved = resolvedCompletion) {
                    RuntimeStartCompletion.Cancelled,
                    RuntimeStartCompletion.Finished,
                    -> current.finishRuntimeStartDisconnected()
                    is RuntimeStartCompletion.HandledFailure ->
                        resolved.disposition
                            .stateTransform(current)
                            .finishRuntimeStartDisconnected()
                    is RuntimeStartCompletion.Failure -> current.finishRuntimeStartFailed()
                    RuntimeStartCompletion.Superseded -> current.finishRuntimeStartPreservingStatus()
                    is RuntimeStartCompletion.Connected -> current
                }
            }
            if (resolvedCompletion is RuntimeStartCompletion.HandledFailure) {
                val disposition = (resolvedCompletion as RuntimeStartCompletion.HandledFailure).disposition
                afterCommit = disposition.afterCommit
                userAction = disposition.onUserActionRequired
                committedGeneration = store.runtimeStartGeneration.get()
                committedConnectionSerial = store.state.value.connectionSerial
            }
        }
        afterCommit?.let { callback ->
            synchronized(store) {
                if (canDeliverPostCommitLocked(committedGeneration, committedConnectionSerial)) {
                    runCatching(callback)
                }
            }
        }
        userAction?.let { callback ->
            coroutineScope.launch(CoroutineName("priv-ui-runtime-user-action")) {
                synchronized(store) {
                    if (canDeliverPostCommitLocked(committedGeneration, committedConnectionSerial)) {
                        runCatching(callback)
                    }
                }
            }
        }
    }

    private fun canDeliverPostCommitLocked(
        committedGeneration: Long?,
        committedConnectionSerial: Long?,
    ): Boolean =
        !closed.get() &&
            store.runtimeStartGeneration.get() == committedGeneration &&
            store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE &&
            store.state.value.connectionSerial == committedConnectionSerial

    private fun connectServer(serverInfo: PrivilegeServerInfo) {
        connectServer(serverInfo, expectedSession = null)
    }

    private fun connectServer(
        serverInfo: PrivilegeServerInfo,
        expectedSession: PrivilegeUiRuntimeStartSession?,
    ) {
        if (closed.get()) return
        var activeSession: PrivilegeUiRuntimeStartSession? = null
        var activeStartJob: Job? = null
        synchronized(store) {
            if (closed.get()) return
            if (expectedSession != null && !isCurrentRuntimeStartLocked(expectedSession)) return
            activeSession = store.runtimeStartSession
            val session = activeSession
            if (session != null && !session.recordConnectedServer(serverInfo)) return
            if (session == null) {
                publishConnectedServerLocked(serverInfo)
                return
            }
            activeStartJob = store.runtimeStartJob
        }
        val session = activeSession ?: return
        activeStartJob?.cancel(CancellationException("Runtime server connected"))
        session.signalCompletion()
        session.finish { throwable ->
            appendCleanupFailure(session, throwable)
        }
        synchronized(store) {
            if (closed.get() || !ownsRuntimeStartLocked(session)) return
            store.runtimeStartGeneration.incrementAndGet()
            store.runtimeStartSession = null
            store.runtimeStartJob = null
            val connectedServer = session.latestConnectedServer()
            if (connectedServer == null) {
                store.updateState { it.finishRuntimeStartDisconnected() }
            } else {
                publishConnectedServerLocked(connectedServer)
            }
        }
    }

    private fun publishConnectedServerLocked(serverInfo: PrivilegeServerInfo) {
        val shouldAppendLog = store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = serverInfo,
                runtimeProgressMessage = null,
                connectionSerial = it.connectionSerial + 1L,
            )
        }
        if (shouldAppendLog && !closed.get()) {
            store.appendStartupLog(store.text(R.string.priv_ui_connected))
        }
    }

    private fun updateCurrentRuntimeStartAttempt(
        session: PrivilegeUiRuntimeStartSession,
        attempt: PrivilegeUiRuntimeStartAttempt,
    ) {
        updateCurrentRuntimeStartState(session) {
            if (it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.RUNNING) {
                it.startingAttempt(attempt, PrivilegeUiRuntimeStartPhase.RUNNING)
            } else {
                it
            }
        }
    }

    private fun updateCurrentRuntimeStartState(
        session: PrivilegeUiRuntimeStartSession,
        transform: (PrivilegeUiState) -> PrivilegeUiState,
    ) {
        synchronized(store) {
            if (!isCurrentRuntimeStartLocked(session) || closed.get()) return
            store.updateState(transform)
        }
    }

    private fun isCurrentRuntimeStart(session: PrivilegeUiRuntimeStartSession): Boolean =
        synchronized(store) { isCurrentRuntimeStartLocked(session) }

    private fun isCurrentRuntimeStartLocked(session: PrivilegeUiRuntimeStartSession): Boolean =
        ownsRuntimeStartLocked(session) && !session.finished && !session.connectionClaimed

    private fun ownsRuntimeStartLocked(session: PrivilegeUiRuntimeStartSession): Boolean =
        store.runtimeStartGeneration.get() == session.generation &&
            store.runtimeStartSession === session

    private fun appendStartupSource(
        session: PrivilegeUiRuntimeStartSession,
        startupSource: String?,
    ) {
        val source = startupSource?.trim()?.takeIf { it.isNotEmpty() } ?: return
        session.appendStartupLog(store.text(R.string.priv_ui_startup_source, source))
    }

    private fun appendSessionStartupLog(
        session: PrivilegeUiRuntimeStartSession,
        line: String,
    ) {
        synchronized(store) {
            if (!closed.get() && isCurrentRuntimeStartLocked(session)) {
                store.appendStartupLog(line)
            }
        }
    }

    private fun appendSessionStartupLog(
        session: PrivilegeUiRuntimeStartSession,
        line: PrivilegeStartupLogLine,
    ) {
        synchronized(store) {
            if (!closed.get() && isCurrentRuntimeStartLocked(session)) {
                store.appendStartupLog(line)
            }
        }
    }

    private fun appendCleanupFailure(
        session: PrivilegeUiRuntimeStartSession,
        throwable: Throwable,
    ) {
        synchronized(store) {
            if (!closed.get() && ownsRuntimeStartLocked(session)) {
                store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
            }
        }
    }

    private fun PrivilegeUiRuntimeStartAttempt.startFailureDisposition(
        throwable: Throwable,
    ): PrivilegeUiRuntimeStartFailureDisposition? =
        when (this) {
            is PrivilegeUiRuntimeStartAttempt.Connect -> onFailure?.invoke(throwable)
            is PrivilegeUiRuntimeStartAttempt.Workflow -> onFailure?.invoke(throwable)
            is PrivilegeUiRuntimeStartAttempt.Request -> null
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
            store.updateState {
                if (it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE) {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        runtimeStartSource = null,
                        runtimeStartProviderId = null,
                        serverInfo = null,
                        runtimeProgressMessage = null,
                    )
                } else {
                    it.copy(
                        runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                        serverInfo = null,
                    )
                }
            }
        }
        store.appendLog(message)
    }

    private fun PrivilegeUiState.startingAttempt(
        attempt: PrivilegeUiRuntimeStartAttempt,
        phase: PrivilegeUiRuntimeStartPhase,
    ): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            copy(
                busy = true,
                runtimeStartPhase = phase,
                runtimeStartSource = attempt.runtimeStartSource,
                runtimeStartProviderId = attempt.runtimeStartProviderId,
                runtimeProgressMessage = attempt.message,
            )
        } else {
            copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                runtimeStartPhase = phase,
                runtimeStartSource = attempt.runtimeStartSource,
                runtimeStartProviderId = attempt.runtimeStartProviderId,
                serverInfo = null,
                runtimeProgressMessage = attempt.message,
            )
        }

    private fun PrivilegeUiState.finishRuntimeStartPreservingStatus(): PrivilegeUiState =
        copy(
            busy = false,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            runtimeStartSource = null,
            runtimeStartProviderId = null,
            runtimeProgressMessage = null,
        )

    private fun PrivilegeUiState.finishRuntimeStartDisconnected(): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            finishRuntimeStartPreservingStatus()
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = null,
                runtimeProgressMessage = null,
            )
        }

    private fun PrivilegeUiState.finishRuntimeStartFailed(): PrivilegeUiState =
        if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            finishRuntimeStartPreservingStatus()
        } else {
            copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = null,
                runtimeProgressMessage = null,
            )
        }
}

internal sealed interface PrivilegeUiRuntimeStartAttempt {
    val message: String
    val startupSource: String?
    val runtimeStartSource: PrivilegeUiRuntimeStartSource?
    val runtimeStartProviderId: String?

    class Connect(
        override val message: String,
        override val startupSource: String?,
        override val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
        val onFailure: ((Throwable) -> PrivilegeUiRuntimeStartFailureDisposition?)? = null,
        override val runtimeStartProviderId: String? = null,
        val start: PrivilegeUiRuntimeStartSession.() -> PrivilegeServerInfo,
    ) : PrivilegeUiRuntimeStartAttempt

    class Request(
        override val message: String,
        val startedMessage: String,
        override val startupSource: String?,
        override val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
        override val runtimeStartProviderId: String? = null,
        val start: PrivilegeUiRuntimeStartSession.() -> Unit,
    ) : PrivilegeUiRuntimeStartAttempt

    class Workflow(
        override val message: String,
        override val startupSource: String?,
        override val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
        val onFailure: ((Throwable) -> PrivilegeUiRuntimeStartFailureDisposition?)? = null,
        override val runtimeStartProviderId: String? = null,
        val start: suspend PrivilegeUiRuntimeStartSession.() -> PrivilegeUiRuntimeStartResult,
    ) : PrivilegeUiRuntimeStartAttempt
}

internal class PrivilegeUiRuntimeStartFailureDisposition(
    val stateTransform: (PrivilegeUiState) -> PrivilegeUiState = { it },
    val snackbarMessage: String? = null,
    val startupLogLines: List<String> = emptyList(),
    val afterCommit: (() -> Unit)? = null,
    val onUserActionRequired: (() -> Unit)? = null,
)

internal sealed interface PrivilegeUiRuntimeStartResult {
    class Connected(val serverInfo: PrivilegeServerInfo) : PrivilegeUiRuntimeStartResult
    class RequestSent(val message: String) : PrivilegeUiRuntimeStartResult
    data object Finished : PrivilegeUiRuntimeStartResult
}

private sealed interface RuntimeStartCompletion {
    class Connected(val serverInfo: PrivilegeServerInfo) : RuntimeStartCompletion
    class Failure(
        val throwable: Throwable,
        val appendDiagnostic: Boolean = true,
    ) : RuntimeStartCompletion

    data object Cancelled : RuntimeStartCompletion
    data object Finished : RuntimeStartCompletion
    class HandledFailure(
        val disposition: PrivilegeUiRuntimeStartFailureDisposition,
    ) : RuntimeStartCompletion
    data object Superseded : RuntimeStartCompletion
}
