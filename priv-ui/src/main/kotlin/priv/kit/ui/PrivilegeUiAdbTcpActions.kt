package priv.kit.ui

import priv.kit.Privilege
import priv.kit.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.adb.PrivilegeAdbStartOptions

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

    fun cancelTcpAuthorization() {
        finishTcpAuthorizationRequest()
    }

    fun finishPendingTcpAuthorizationOnHostResume() {
        if (store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
            finishTcpAuthorizationRequest()
        }
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
            serverInfo
        }
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
