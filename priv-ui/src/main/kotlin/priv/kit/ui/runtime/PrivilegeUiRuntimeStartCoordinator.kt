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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeStartupLogLine
import priv.kit.internal.runtime.PrivilegeRuntimeConnectionEvent
import priv.kit.internal.runtime.PrivilegeRuntimeStartCoordinator

internal class PrivilegeUiRuntimeStartCoordinator(
    private val store: PrivilegeUiViewModelStore,
    private val isClosed: () -> Boolean,
    private val publishConnectedServer: (PrivilegeServerInfo) -> Unit,
    private val acquireStartPermit: () -> AutoCloseable?,
) {
    private val runtimeStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completionHandler = PrivilegeUiRuntimeStartCompletionHandler(
        store = store,
        isClosed = isClosed,
        isCurrentRuntimeStartLocked = ::isCurrentRuntimeStartLocked,
        ownsRuntimeStartLocked = ::ownsRuntimeStartLocked,
        publishConnectedServer = publishConnectedServer,
        recordSuccessfulStartMethod = { method ->
            store.applicationContext?.let { context ->
                PrivilegeUiStartMethodStore(context).write(method)
            }
        },
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

    fun runServerStart(attempt: PrivilegeUiRuntimeStartAttempt.Connect): Boolean =
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start")

    fun runServerStartRequest(attempt: PrivilegeUiRuntimeStartAttempt.Request): Boolean =
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start-request")

    fun runServerStartWorkflow(attempt: PrivilegeUiRuntimeStartAttempt.Workflow): Boolean =
        runServerStartAttempt(attempt, name = "priv-ui-runtime-start-workflow")

    fun runServerStartFallback(attempts: List<PrivilegeUiRuntimeStartAttempt>): Boolean {
        if (attempts.isEmpty()) {
            reportStartFailedIfIdle()
            return false
        }
        return launchRuntimeStart(
            initialAttempt = attempts.first(),
            showAttemptFeedback = false,
            name = "priv-ui-runtime-start-fallback",
            beforeStart = { store.clearStartupLog() },
        ) { session ->
            attemptRunner.runFallbackAttempts(session, attempts)
        }
    }

    fun handleServerConnected(
        event: PrivilegeRuntimeConnectionEvent,
        deduplicatePassiveConnection: Boolean,
    ) {
        completionHandler.handleServerConnected(
            event = event,
            deduplicatePassiveConnection = deduplicatePassiveConnection,
        )
    }

    fun handlePassiveServerConnected(
        serverInfo: PrivilegeServerInfo,
        deduplicatePassiveConnection: Boolean,
    ) {
        completionHandler.handlePassiveServerConnected(
            serverInfo = serverInfo,
            deduplicatePassiveConnection = deduplicatePassiveConnection,
        )
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
    ): Boolean =
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

    private fun launchRuntimeStart(
        initialAttempt: PrivilegeUiRuntimeStartAttempt,
        showAttemptFeedback: Boolean = true,
        name: String,
        beforeStart: (PrivilegeUiRuntimeStartSession) -> Unit,
        block: suspend (PrivilegeUiRuntimeStartSession) -> RuntimeStartCompletion,
    ): Boolean {
        val startPermit = acquireStartPermit() ?: return false
        val permitLease = PrivilegeUiStartPermitLease(startPermit)
        val runtimePreflight = PrivilegeRuntimeStartCoordinator.beginPreflight()
        return try {
            synchronized(store) {
                val current = store.state.value
                if (
                    isClosed() ||
                    current.busy ||
                    current.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
                    store.runtimeStartSession != null ||
                    store.runtimeStartJob != null
                ) {
                    permitLease.releaseNow()
                    return@synchronized false
                }

                val session = PrivilegeUiRuntimeStartSession(
                    generation = store.runtimeStartGeneration.incrementAndGet(),
                    showAttemptFeedback = showAttemptFeedback,
                    recordSuccessfulMethod = true,
                    onConnectionClaimed = permitLease::markConnectionClaimed,
                    onConnectionHandled = permitLease::markConnectionHandled,
                    onOwnerCleanupRequired = permitLease::markOwnerCleanupRequired,
                    onOwnerCleanupCompleted = permitLease::markOwnerCleanupCompleted,
                    startupLogSink = ::appendSessionStartupLog,
                    structuredStartupLogSink = ::appendSessionStartupLog,
                )
                val job: Job = runtimeStartScope.launch(
                    context = CoroutineName(name),
                    start = CoroutineStart.LAZY,
                ) {
                    val completion = try {
                        if (runtimePreflight.remainingReconnectGraceMillis > 0L) {
                            delay(runtimePreflight.remainingReconnectGraceMillis)
                        }
                        session.checkActive()
                        val runtimeLease =
                            PrivilegeRuntimeStartCoordinator.tryCommitClientStart(runtimePreflight)
                        if (runtimeLease == null) {
                            RuntimeStartCompletion.Superseded
                        } else if (!session.attachRuntimeStartLease(runtimeLease)) {
                            RuntimeStartCompletion.Cancelled
                        } else {
                            block(session)
                        }
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
                    try {
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
                    } finally {
                        session.markRuntimeStartJobCompleted()
                        synchronized(store) {
                            permitLease.markJobCompleted(
                                noConnectionCanBeClaimed = store.runtimeStartSession !== session,
                            )
                        }
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
                try {
                    beforeStart(session)
                    job.start()
                } catch (throwable: Throwable) {
                    store.runtimeStartGeneration.incrementAndGet()
                    store.runtimeStartSession = null
                    store.runtimeStartJob = null
                    store.updateState { it.finishRuntimeStartFailed() }
                    session.finish()
                    job.cancel(CancellationException("Runtime start setup failed"))
                    permitLease.releaseNow()
                    throw throwable
                }
                true
            }
        } catch (throwable: Throwable) {
            permitLease.releaseNow()
            throw throwable
        }
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
                session.markOwnerCleanupRequired()
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
                if (ownerClosing) {
                    session.markOwnerCleanupCompleted()
                    cleanupScope.cancel()
                }
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
