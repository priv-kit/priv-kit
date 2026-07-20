package priv.kit.ui.runtime

import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStatus
import priv.kit.ui.R
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.toPrivilegeUiDiagnosticString

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import priv.kit.PrivilegeServerInfo
import priv.kit.internal.runtime.PrivilegeRuntimeConnectionEvent

internal class PrivilegeUiRuntimeStartCompletionHandler(
    private val store: PrivilegeUiViewModelStore,
    private val isClosed: () -> Boolean,
    private val isCurrentRuntimeStartLocked: (PrivilegeUiRuntimeStartSession) -> Boolean,
    private val ownsRuntimeStartLocked: (PrivilegeUiRuntimeStartSession) -> Boolean,
    private val publishConnectedServer: (PrivilegeServerInfo) -> Unit,
    private val recordSuccessfulStartMethod: (PrivilegeUiStartMethod) -> Unit,
) {
    fun completeRuntimeStart(
        session: PrivilegeUiRuntimeStartSession,
        completion: RuntimeStartCompletion,
    ) {
        if (completion is RuntimeStartCompletion.Connected) {
            handleServerConnected(
                serverInfo = completion.serverInfo,
                expectedSession = session,
                belongsToExpectedSession = true,
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
            if (!isClosed()) {
                when (val resolved = resolvedCompletion) {
                    RuntimeStartCompletion.Cancelled -> {
                        store.appendStartupLog(store.text(R.string.priv_ui_startup_interrupted))
                    }
                    is RuntimeStartCompletion.Failure -> {
                        store.showFailure(resolved.failureKind)
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
                val disposition = resolvedCompletion.disposition
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
            synchronized(store) {
                if (canDeliverPostCommitLocked(committedGeneration, committedConnectionSerial)) {
                    runCatching(callback)
                }
            }
        }
    }

    fun handleServerConnected(
        event: PrivilegeRuntimeConnectionEvent,
        deduplicatePassiveConnection: Boolean = false,
    ) {
        handleServerConnected(
            serverInfo = event.serverInfo,
            expectedSession = null,
            connectionEvent = event,
            deduplicatePassiveConnection = deduplicatePassiveConnection,
        )
    }

    fun handlePassiveServerConnected(
        serverInfo: PrivilegeServerInfo,
        deduplicatePassiveConnection: Boolean = false,
    ) {
        handleServerConnected(
            serverInfo = serverInfo,
            expectedSession = null,
            deduplicatePassiveConnection = deduplicatePassiveConnection,
        )
    }

    fun appendCleanupFailure(
        session: PrivilegeUiRuntimeStartSession,
        throwable: Throwable,
    ) {
        synchronized(store) {
            if (!isClosed() && ownsRuntimeStartLocked(session)) {
                store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
            }
        }
    }

    private fun canDeliverPostCommitLocked(
        committedGeneration: Long?,
        committedConnectionSerial: Long?,
    ): Boolean =
        !isClosed() &&
            store.runtimeStartGeneration.get() == committedGeneration &&
            store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE &&
            store.state.value.connectionSerial == committedConnectionSerial

    private fun handleServerConnected(
        serverInfo: PrivilegeServerInfo,
        expectedSession: PrivilegeUiRuntimeStartSession?,
        connectionEvent: PrivilegeRuntimeConnectionEvent? = null,
        belongsToExpectedSession: Boolean = false,
        deduplicatePassiveConnection: Boolean = false,
    ) {
        if (isClosed()) return
        var activeSession: PrivilegeUiRuntimeStartSession? = null
        var activeStartJob: Job? = null
        var successfulStartMethod: PrivilegeUiStartMethod? = null
        synchronized(store) {
            if (isClosed()) return
            if (expectedSession != null && !isCurrentRuntimeStartLocked(expectedSession)) return
            val current = store.state.value
            if (
                deduplicatePassiveConnection &&
                store.runtimeStartSession == null &&
                current.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE &&
                current.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED &&
                current.serverInfo == serverInfo
            ) {
                return
            }
            activeSession = store.runtimeStartSession
            val session = activeSession
            val belongsToCurrentStart = when {
                session == null -> false
                expectedSession != null -> belongsToExpectedSession
                connectionEvent == null -> false
                else -> session.ownsRuntimeConnection(
                    origin = connectionEvent.origin,
                    clientStartOperationId = connectionEvent.clientStartOperationId,
                    initialLaunchId = connectionEvent.initialLaunchId,
                )
            }
            val connectionClaim = session?.recordConnectedServer(
                serverInfo = serverInfo,
                belongsToCurrentStart = belongsToCurrentStart,
            )
            if (session != null && connectionClaim == null) return
            if (session == null) {
                publishConnectedServer(serverInfo)
                return
            }
            successfulStartMethod = connectionClaim?.successfulMethod
            activeStartJob = store.runtimeStartJob
        }
        val session = activeSession ?: return
        try {
            activeStartJob?.cancel(CancellationException("Runtime server connected"))
            session.signalCompletion()
            session.finish { throwable ->
                appendCleanupFailure(session, throwable)
            }
            synchronized(store) {
                if (isClosed() || !ownsRuntimeStartLocked(session)) return
                val connectedServer = session.latestConnectedServer()
                if (connectedServer != null) {
                    successfulStartMethod?.let { method ->
                        runCatching { recordSuccessfulStartMethod(method) }
                    }
                }
                store.runtimeStartGeneration.incrementAndGet()
                store.runtimeStartSession = null
                store.runtimeStartJob = null
                if (connectedServer == null) {
                    store.updateState { it.finishRuntimeStartDisconnected() }
                } else {
                    publishConnectedServer(connectedServer)
                }
            }
        } finally {
            session.markConnectionHandled()
        }
    }
}
