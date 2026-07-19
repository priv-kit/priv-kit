package priv.kit.ui.adb

import kotlinx.coroutines.suspendCancellableCoroutine
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo
import priv.kit.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.adb.PrivilegeAdbAuthorizationRequestResult
import priv.kit.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.R
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartSession
import priv.kit.ui.state.PrivilegeUiFailureKind
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.privilegeUiTcpAuthorizationFailureKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class PrivilegeUiAdbTcpActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val refreshTcpModeEnabled: () -> Unit,
    private val tcpAuthorizationRequester: ((
        tcpPort: Int,
        timeoutMillis: Long,
        callback: (PrivilegeAdbAuthorizationRequestResult) -> Unit,
    ) -> AutoCloseable)? = null,
    private val beforeTcpAuthorizationResultCommit: () -> Unit = {},
) : AutoCloseable {
    fun enableTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.config.tcpPort
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_enabling),
            failureKind = PrivilegeUiFailureKind.TCP_ENABLE_FAILED,
            onFailure = {
                store.updateTcpModePort(null)
            },
            action = {
                val starter = Privilege.createAdbStarter(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                )
                starter.switchToTcp(
                    tcpPort = tcpPort,
                )
            },
            onSuccess = { result ->
                store.updateTcpModePort(result.port)
                store.updateConfiguredTcpModePort(result.port)
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_enabled)
            },
        )
    }

    suspend fun requestTcpAuthorizationForStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int,
    ): Boolean {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return false
        session.appendStartupLog(store.text(R.string.priv_ui_adb_static_authorize_action))
        return suspendCancellableCoroutine { continuation ->
            var previousRequest: AutoCloseable? = null
            var requestGeneration = 0L
            synchronized(store) {
                previousRequest = store.tcpAuthorizationRequest
                requestGeneration = store.tcpAuthorizationRequestGeneration.incrementAndGet()
                store.tcpAuthorizationRequest = null
                store.updateState {
                    it.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                    )
                }
            }
            runCatching { previousRequest?.close() }

            val completed = AtomicBoolean(false)
            val resultCommitted = AtomicBoolean(false)
            val requestLock = Any()
            lateinit var request: AutoCloseable

            fun finish(result: PrivilegeAdbAuthorizationRequestResult) {
                var authorized = false
                synchronized(requestLock) {
                    if (!completed.compareAndSet(false, true)) return
                    beforeTcpAuthorizationResultCommit()
                    synchronized(store) {
                        if (
                            store.tcpAuthorizationRequestGeneration.get() == requestGeneration &&
                            ownsTcpAuthorizationSessionLocked(session)
                        ) {
                            store.tcpAuthorizationRequest = null
                            authorized = applyTcpAuthorizationResult(
                                session = session,
                                result = result,
                                showFailure = session.showAttemptFeedback && !session.stop.get(),
                            )
                            resultCommitted.set(true)
                        }
                    }
                }
                if (continuation.isActive) continuation.resume(authorized)
            }

            val callback: (PrivilegeAdbAuthorizationRequestResult) -> Unit = { result -> finish(result) }
            request = tcpAuthorizationRequester?.invoke(
                tcpPort,
                store.config.adbAuthorizationTimeoutMillis,
                callback,
            ) ?: Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            ).requestTcpAuthorization(
                tcpPort = tcpPort,
                timeoutMillis = store.config.adbAuthorizationTimeoutMillis,
                callback = callback,
            )
            val sessionRequest = AutoCloseable {
                var closeRequest = false
                synchronized(requestLock) {
                    closeRequest = completed.compareAndSet(false, true)
                    synchronized(store) {
                        if (store.tcpAuthorizationRequestGeneration.get() == requestGeneration) {
                            store.tcpAuthorizationRequestGeneration.incrementAndGet()
                            store.tcpAuthorizationRequest = null
                            if (!resultCommitted.get()) {
                                markTcpAuthorizationUnauthorizedIfAuthorizing()
                            }
                        }
                    }
                }
                if (closeRequest) runCatching { request.close() }
            }
            continuation.invokeOnCancellation {
                sessionRequest.close()
            }
            session.addCloseable(sessionRequest)
            var installed = false
            synchronized(requestLock) {
                if (!completed.get()) {
                    synchronized(store) {
                        if (
                            store.tcpAuthorizationRequestGeneration.get() == requestGeneration &&
                            ownsTcpAuthorizationSessionLocked(session) &&
                            store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
                        ) {
                            store.tcpAuthorizationRequest = sessionRequest
                            installed = true
                        }
                    }
                }
            }
            if (!installed) sessionRequest.close()
        }
    }

    private fun ownsTcpAuthorizationSessionLocked(session: PrivilegeUiRuntimeStartSession): Boolean =
        !runtimeActions.isClosed &&
            store.runtimeStartGeneration.get() == session.generation &&
            store.runtimeStartSession === session &&
            !session.cancellationRequested &&
            !session.finished &&
            !session.connectionClaimed

    fun tcpAdbStartAttempt(tcpPort: Int): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
        ) {
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            val activeTcpPort = requireStaticTcpReady(starter, this)
            startTcpAdbNow(activeTcpPort, this)
        }
    }

    fun prepareStaticTcpForStart(
        tcpPort: Int,
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeUiStaticTcpPreparationResult {
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val initialAuthorization = starter.prepareTcpForStart(tcpPort = tcpPort)
        val configuredTcpPort = starter.getConfiguredTcpPort()
        val activeTcpPort = starter.getActiveTcpPort()
        val authorization = when {
            configuredTcpPort == null -> null
            configuredTcpPort == tcpPort -> initialAuthorization
            else -> starter.prepareTcpForStart(tcpPort = configuredTcpPort)
        }
        val authorizationStatus = authorization
            ?.status
            ?.toUiTcpAuthorizationStatus()
            ?: PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN
        store.updateTcpModePort(activeTcpPort)
        store.updateConfiguredTcpModePort(configuredTcpPort)
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = authorizationStatus,
            )
        }
        authorization
            ?.failureMessage
            ?.takeIf { it.startsWith("Failed to prepare ADB for static TCP") }
            ?.let(session::appendStartupLog)
        return PrivilegeUiStaticTcpPreparationResult(
            tcpPort = configuredTcpPort,
            authorizationStatus = authorizationStatus,
        )
    }

    fun requireStaticTcpReady(
        starter: PrivilegeAdbStarter,
        session: PrivilegeUiRuntimeStartSession,
    ): Int {
        val configuredTcpPort = starter.getConfiguredTcpPort()
        store.updateConfiguredTcpModePort(configuredTcpPort)
        if (configuredTcpPort == null) {
            store.updateTcpModePort(null)
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            throwStaticTcpStartFailed(session)
        }
        val authorization = starter.prepareTcpForStart(tcpPort = configuredTcpPort)
        val activeTcpPort = starter.getActiveTcpPort()
        store.updateTcpModePort(activeTcpPort)
        store.updateState {
            it.copy(tcpAuthorizationStatus = authorization.status.toUiTcpAuthorizationStatus())
        }
        if (
            activeTcpPort != null &&
            authorization.status == PrivilegeAdbAuthorizationStatus.AUTHORIZED
        ) {
            return activeTcpPort
        }
        authorization.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let(session::appendStartupLog)
        throwStaticTcpStartFailed(session)
    }

    fun startTcpAdbNow(
        tcpPort: Int,
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeServerInfo {
        val serverInfo = Privilege.startAdb(
            options = PrivilegeAdbStartOptions(
                port = tcpPort,
                discoverPort = false,
            ),
            timeoutMillis = store.config.startTimeoutMillis,
            adbDeviceName = store.currentAdbDeviceNameOverride(),
            startupLogListener = session.startupLogListener,
        )
        store.updateTcpModePort(tcpPort)
        store.updateConfiguredTcpModePort(tcpPort)
        return serverInfo
    }

    private fun throwStaticTcpStartFailed(session: PrivilegeUiRuntimeStartSession): Nothing {
        val message = store.text(R.string.priv_ui_adb_static_start_failed)
        session.appendStartupLog(message)
        throw IllegalStateException(message)
    }

    override fun close() {
        var request: AutoCloseable? = null
        synchronized(store) {
            request = store.tcpAuthorizationRequest
            store.tcpAuthorizationRequestGeneration.incrementAndGet()
            store.tcpAuthorizationRequest = null
        }
        runCatching { request?.close() }
    }

    private fun applyTcpAuthorizationResult(
        session: PrivilegeUiRuntimeStartSession,
        result: PrivilegeAdbAuthorizationRequestResult,
        showFailure: Boolean,
    ): Boolean {
        val status = if (result.authorized) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        } else {
            result.endReason.toUiTcpAuthorizationStatus()
        }
        store.updateState { current ->
            current.copy(tcpAuthorizationStatus = status)
        }
        if (result.authorized) {
            session.appendStartupLog(store.text(R.string.priv_ui_tcp_authorization_allowed))
        } else {
            val failureKind = privilegeUiTcpAuthorizationFailureKind(result.endReason)
            result.failureMessage
                ?.takeIf { it.isNotBlank() }
                ?.let(session::appendStartupLog)
            if (showFailure) {
                store.showFailure(failureKind)
                session.appendStartupLog(store.text(failureKind.messageResId))
            }
        }
        return result.authorized
    }

    private fun markTcpAuthorizationUnauthorizedIfAuthorizing() {
        store.updateState { current ->
            if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                current.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED)
            } else {
                current
            }
        }
    }
}

internal data class PrivilegeUiStaticTcpPreparationResult(
    val tcpPort: Int?,
    val authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
)

private fun PrivilegeAdbAuthorizationEndReason?.toUiTcpAuthorizationStatus(): PrivilegeUiAdbTcpAuthorizationStatus =
    when (this) {
        PrivilegeAdbAuthorizationEndReason.FAILED -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
        PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
        PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
        null,
        -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
    }
