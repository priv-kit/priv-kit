package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeStartupLogLine

internal class PrivilegeUiRuntimeStartCoordinator(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val isClosed: () -> Boolean,
    private val publishConnectedServer: (PrivilegeServerInfo) -> Unit,
) {
    private val runtimeStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completionHandler = PrivilegeUiRuntimeStartCompletionHandler(
        store = store,
        coroutineScope = coroutineScope,
        isClosed = isClosed,
        isCurrentRuntimeStartLocked = ::isCurrentRuntimeStartLocked,
        ownsRuntimeStartLocked = ::ownsRuntimeStartLocked,
        publishConnectedServer = publishConnectedServer,
    )
    private val attemptRunner = PrivilegeUiRuntimeStartAttemptRunner(
        store = store,
        isClosed = isClosed,
        isCurrentRuntimeStart = ::isCurrentRuntimeStart,
        updateCurrentRuntimeStartAttempt = ::updateCurrentRuntimeStartAttempt,
        updateCurrentRuntimeStartState = ::updateCurrentRuntimeStartState,
        applySilentFallbackFailureDisposition = ::applySilentFallbackFailureDisposition,
        appendStartupSource = ::appendStartupSource,
    )

    fun stopCurrentStart() {
        if (store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.RUNNING) {
            requestRuntimeStartCancellation()
        }
    }

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect) {
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start")
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
            attemptRunner.runFallbackAttempts(session, attempts)
        }
    }

    fun handleServerConnected(serverInfo: PrivilegeServerInfo) {
        completionHandler.handleServerConnected(serverInfo)
    }

    fun close() {
        val hadRuntimeStart = requestRuntimeStartCancellation(ownerClosing = true)
        runtimeStartScope.cancel()
        if (!hadRuntimeStart) cleanupScope.cancel()
    }

    private fun reportStartFailedIfIdle() {
        synchronized(store) {
            val current = store.state.value
            if (
                isClosed() ||
                current.busy ||
                current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
                current.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
            ) {
                return
            }
            val message = store.text(R.string.priv_ui_start_failed)
            store.showFailure(PrivilegeUiFailureKind.START_FAILED)
            store.appendLog(message)
        }
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
            attemptRunner.runSingleAttempt(session, attempt)
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
            isClosed() ||
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
        val job: Job = runtimeStartScope.launch(
            context = CoroutineName(name),
            start = CoroutineStart.LAZY,
        ) {
            val completion = try {
                block(session)
            } catch (_: CancellationException) {
                RuntimeStartCompletion.Cancelled
            } catch (throwable: Throwable) {
                RuntimeStartCompletion.Failure(
                    throwable = throwable,
                    failureKind = privilegeUiRuntimeStartFailureKind(
                        runtimeStartSource = initialAttempt.runtimeStartSource,
                        throwable = throwable,
                    ),
                )
            }
            completionHandler.completeRuntimeStart(session, completion)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                val completion = if (
                    cause is CancellationException ||
                    session.cancellationRequested
                ) {
                    RuntimeStartCompletion.Cancelled
                } else {
                    RuntimeStartCompletion.Failure(
                        throwable = cause,
                        failureKind = privilegeUiRuntimeStartFailureKind(
                            runtimeStartSource = initialAttempt.runtimeStartSource,
                            throwable = cause,
                        ),
                    )
                }
                completionHandler.completeRuntimeStart(session, completion)
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

    private fun applySilentFallbackFailureDisposition(
        session: PrivilegeUiRuntimeStartSession,
        disposition: PrivilegeUiRuntimeStartFailureDisposition,
    ) {
        var afterCommit: (() -> Unit)? = null
        synchronized(store) {
            if (
                isClosed() ||
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

    private fun requestRuntimeStartCancellation(ownerClosing: Boolean = false): Boolean {
        lateinit var session: PrivilegeUiRuntimeStartSession
        var job: Job? = null
        var firstRequest: Boolean
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
        cleanupScope.launch(CoroutineName("priv-ui-runtime-start-cleanup")) {
            try {
                session.closeCancellationResources { throwable ->
                    completionHandler.appendCleanupFailure(session, throwable)
                }
                if (ownerClosing) {
                    session.finish { throwable ->
                        completionHandler.appendCleanupFailure(session, throwable)
                    }
                }
            } finally {
                if (ownerClosing) cleanupScope.cancel()
            }
        }
        return true
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
            if (!isCurrentRuntimeStartLocked(session) || isClosed()) return
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
            if (!isClosed() && isCurrentRuntimeStartLocked(session)) {
                store.appendStartupLog(line)
            }
        }
    }

    private fun appendSessionStartupLog(
        session: PrivilegeUiRuntimeStartSession,
        line: PrivilegeStartupLogLine,
    ) {
        synchronized(store) {
            if (!isClosed() && isCurrentRuntimeStartLocked(session)) {
                store.appendStartupLog(line)
            }
        }
    }

}
