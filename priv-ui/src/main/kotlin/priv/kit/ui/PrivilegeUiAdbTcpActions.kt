package priv.kit.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo
import priv.kit.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.adb.PrivilegeAdbAuthorizationRequestResult
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class PrivilegeUiAdbTcpActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val refreshTcpModeEnabled: () -> Unit,
) : AutoCloseable {
    fun enableTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.config.tcpPort
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_enabling),
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
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_enabled)
            },
        )
    }

    fun requestTcpAuthorization(tcpPort: Int? = store.currentTcpModePort()) {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        if (tcpPort == null) {
            refreshTcpModeEnabled()
            return
        }
        store.tcpAuthorizationRequest?.let { request ->
            if (store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                return
            }
            store.tcpAuthorizationRequestGeneration.incrementAndGet()
            store.tcpAuthorizationRequest = null
            request.close()
        }
        val requestGeneration = store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            )
        }
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val request = starter.requestTcpAuthorization(
            tcpPort = tcpPort,
            timeoutMillis = store.config.adbAuthorizationTimeoutMillis,
        ) { result ->
            if (store.tcpAuthorizationRequestGeneration.get() != requestGeneration) {
                return@requestTcpAuthorization
            }
            store.tcpAuthorizationRequest = null
            if (result.authorized) {
                val message = store.text(R.string.priv_ui_tcp_authorization_allowed)
                store.updateState {
                    it.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
                    )
                }
                store.appendLog(message)
            } else {
                val status = when (result.endReason) {
                    PrivilegeAdbAuthorizationEndReason.FAILED -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
                    PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
                    PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
                    null,
                    -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
                }
                val message = result.failureMessage
                    ?: store.text(R.string.priv_ui_tcp_authorization_not_completed)
                store.updateState {
                    it.copy(
                        tcpAuthorizationStatus = status,
                    )
                }
                store.showFailure(message)
                store.appendLog(message)
            }
        }
        if (
            store.tcpAuthorizationRequestGeneration.get() == requestGeneration &&
            store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
        ) {
            store.tcpAuthorizationRequest = request
        } else {
            request.close()
        }
    }

    suspend fun requestTcpAuthorizationForStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int,
    ): Boolean {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return false
        store.appendStartupLog(store.text(R.string.priv_ui_adb_static_authorize_action))
        return suspendCancellableCoroutine { continuation ->
            val previousRequest = store.tcpAuthorizationRequest
            store.tcpAuthorizationRequestGeneration.incrementAndGet()
            store.tcpAuthorizationRequest = null
            previousRequest?.close()

            val requestGeneration = store.tcpAuthorizationRequestGeneration.incrementAndGet()
            val completed = AtomicBoolean(false)
            store.updateState {
                it.copy(
                    tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                )
            }
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            lateinit var request: AutoCloseable

            fun finish(result: PrivilegeAdbAuthorizationRequestResult) {
                if (!completed.compareAndSet(false, true)) return
                if (store.tcpAuthorizationRequestGeneration.get() != requestGeneration) {
                    if (continuation.isActive) continuation.resume(false)
                    return
                }
                store.tcpAuthorizationRequest = null
                if (result.authorized) {
                    val message = store.text(R.string.priv_ui_tcp_authorization_allowed)
                    store.updateState {
                        it.copy(
                            tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
                        )
                    }
                    store.appendStartupLog(message)
                    if (continuation.isActive) continuation.resume(true)
                } else {
                    val status = when (result.endReason) {
                        PrivilegeAdbAuthorizationEndReason.FAILED -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
                        PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
                        PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
                        null,
                        -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
                    }
                    val message = result.failureMessage
                        ?: store.text(R.string.priv_ui_tcp_authorization_not_completed)
                    store.updateState {
                        it.copy(
                            tcpAuthorizationStatus = status,
                        )
                    }
                    if (!session.stop.get()) {
                        store.showFailure(message)
                        store.appendStartupLog(message)
                    }
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            request = starter.requestTcpAuthorization(
                tcpPort = tcpPort,
                timeoutMillis = store.config.adbAuthorizationTimeoutMillis,
                callback = { result -> finish(result) },
            )
            val sessionRequest = AutoCloseable {
                if (completed.compareAndSet(false, true)) {
                    if (store.tcpAuthorizationRequestGeneration.get() == requestGeneration) {
                        store.tcpAuthorizationRequestGeneration.incrementAndGet()
                        store.tcpAuthorizationRequest = null
                        store.updateState { current ->
                            if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                                current.copy(
                                    tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                                )
                            } else {
                                current
                            }
                        }
                    }
                    request.close()
                }
            }
            continuation.invokeOnCancellation {
                sessionRequest.close()
            }
            session.addCloseable(sessionRequest)
            if (
                store.tcpAuthorizationRequestGeneration.get() == requestGeneration &&
                store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
            ) {
                store.tcpAuthorizationRequest = request
            } else {
                sessionRequest.close()
            }
        }
    }

    fun cancelTcpAuthorization() {
        finishTcpAuthorizationRequest()
    }

    fun startTcpAdb(tcpPort: Int? = store.currentTcpModePort()) {
        if (tcpPort == null) {
            refreshTcpModeEnabled()
            return
        }
        runtimeActions.runServerStart(tcpAdbStartAttempt(tcpPort))
    }

    fun tcpAdbStartAttempt(tcpPort: Int): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
        ) {
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            val ready = requireStaticTcpReady(starter)
            startTcpAdbNow(ready.tcpPort)
        }
    }

    fun requireStaticTcpReady(
        starter: PrivilegeAdbStarter,
    ): PrivilegeUiStaticTcpStartCheck.Ready {
        val activeTcpPort = starter.getActiveTcpPort()
        store.updateTcpModePort(activeTcpPort)
        if (activeTcpPort == null) {
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            throwStaticTcpStartFailed()
        }
        val authorization = starter.checkTcpAuthorization(tcpPort = activeTcpPort)
        store.updateState {
            it.copy(tcpAuthorizationStatus = authorization.status.toUiTcpAuthorizationStatus())
        }
        val startCheck = privilegeUiStaticTcpStartCheck(
            activeTcpPort = activeTcpPort,
            authorizationStatus = authorization.status,
        )
        if (startCheck is PrivilegeUiStaticTcpStartCheck.Ready) return startCheck
        authorization.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let(store::appendStartupLog)
        throwStaticTcpStartFailed()
    }

    fun startTcpAdbNow(tcpPort: Int): PrivilegeServerInfo {
        val serverInfo = Privilege.startAdb(
            options = PrivilegeAdbStartOptions(
                port = tcpPort,
                discoverPort = false,
            ),
            timeoutMillis = store.config.startTimeoutMillis,
            adbDeviceName = store.currentAdbDeviceNameOverride(),
            startupLogListener = store.startupLogListener,
        )
        store.updateTcpModePort(tcpPort)
        return serverInfo
    }

    private fun throwStaticTcpStartFailed(): Nothing {
        val message = store.text(R.string.priv_ui_adb_static_start_failed)
        store.appendStartupLog(message)
        throw IllegalStateException(message)
    }

    override fun close() {
        val request = store.tcpAuthorizationRequest
        store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.tcpAuthorizationRequest = null
        request?.close()
    }

    private fun finishTcpAuthorizationRequest() {
        val request = store.tcpAuthorizationRequest
        store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.tcpAuthorizationRequest = null
        if (request == null) {
            store.updateState { current ->
                if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                    current.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                    )
                } else {
                    current
                }
            }
            return
        }
        request.close()
        store.updateState { current ->
            if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                current.copy(
                    tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                )
            } else {
                current
            }
        }
    }
}
