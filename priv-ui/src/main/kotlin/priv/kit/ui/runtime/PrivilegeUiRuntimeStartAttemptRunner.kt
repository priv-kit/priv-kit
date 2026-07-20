package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.PrivilegeServerLaunchUncertainException
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiRuntimeStartAttemptRunner(
    private val store: PrivilegeUiViewModelStore,
    private val isClosed: () -> Boolean,
    private val isCurrentRuntimeStart: (PrivilegeUiRuntimeStartSession) -> Boolean,
    private val updateCurrentRuntimeStartAttempt: (
        PrivilegeUiRuntimeStartSession,
        PrivilegeUiRuntimeStartAttempt,
    ) -> Unit,
    private val updateCurrentRuntimeStartState: (
        PrivilegeUiRuntimeStartSession,
        (PrivilegeUiState) -> PrivilegeUiState,
    ) -> Unit,
    private val applySilentFallbackFailureDisposition: (
        PrivilegeUiRuntimeStartSession,
        PrivilegeUiRuntimeStartFailureDisposition,
    ) -> Unit,
    private val appendStartupSource: (PrivilegeUiRuntimeStartSession, String?) -> Unit,
) {
    suspend fun runSingleAttempt(
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
                    ?: RuntimeStartCompletion.Failure(
                        throwable = throwable,
                        failureKind = privilegeUiRuntimeStartFailureKind(
                            runtimeStartSource = attempt.runtimeStartSource,
                            throwable = throwable,
                        ),
                    )
            }
        }
    }

    suspend fun runFallbackAttempts(
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
                        failureKind = PrivilegeUiFailureKind.START_FAILED,
                        appendDiagnostic = false,
                    )
                }
            }
        }
        return RuntimeStartCompletion.Failure(
            throwable = IllegalStateException(store.text(R.string.priv_ui_start_failed)),
            failureKind = PrivilegeUiFailureKind.START_FAILED,
            appendDiagnostic = false,
        )
    }

    private suspend fun executeRuntimeStartAttempt(
        session: PrivilegeUiRuntimeStartSession,
        attempt: PrivilegeUiRuntimeStartAttempt,
    ): PrivilegeUiRuntimeStartResult {
        session.commitStartMethod(
            privilegeUiStartMethod(
                source = attempt.runtimeStartSource,
                providerId = attempt.runtimeStartProviderId,
            ),
        )
        return when (attempt) {
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
        if (isCurrentRuntimeStart(session) && !isClosed()) {
            session.appendStartupLog(message)
        }

        withContext(NonCancellable) {
            withTimeoutOrNull(store.config.startTimeoutMillis.milliseconds) {
                session.awaitCompletionSignal()
            }
        }

        if (!isCurrentRuntimeStart(session)) return RuntimeStartCompletion.Superseded
        if (session.cancellationRequested) return RuntimeStartCompletion.Cancelled
        return RuntimeStartCompletion.Failure(
            throwable = IllegalStateException(store.text(R.string.priv_ui_start_failed)),
            failureKind = PrivilegeUiFailureKind.START_FAILED,
            appendDiagnostic = false,
        )
    }

    private fun PrivilegeUiRuntimeStartAttempt.startFailureDisposition(
        throwable: Throwable,
    ): PrivilegeUiRuntimeStartFailureDisposition? =
        when (this) {
            is PrivilegeUiRuntimeStartAttempt.Connect -> onFailure?.invoke(throwable)
            is PrivilegeUiRuntimeStartAttempt.Workflow -> onFailure?.invoke(throwable)
            is PrivilegeUiRuntimeStartAttempt.Request -> null
        }
}
