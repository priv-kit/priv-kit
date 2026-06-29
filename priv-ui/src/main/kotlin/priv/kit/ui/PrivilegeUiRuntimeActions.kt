package priv.kit.ui

import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeRuntime
import priv.kit.PrivilegeRuntimeConfig

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
) : AutoCloseable {
    fun configureOwnerDeathBehavior() {
        PrivilegeRuntimeConfig.configure(
            followDeathDelayMillis = store.config.followDeathDelayMillis,
            activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
        )
    }

    fun startRoot() {
        runServerStart(
            message = store.text(R.string.priv_ui_starting_root),
            startupSource = store.text(R.string.priv_ui_auth_method_root),
        ) {
            PrivilegeRuntime.startRoot(
                timeoutMillis = store.config.startTimeoutMillis,
                startupLogListener = store.startupLogListener,
            )
        }
    }

    fun refreshRuntimeStatus() {
        try {
            if (PrivilegeRuntime.pingServer()) {
                connectServer(PrivilegeRuntime.getServerInfo())
            } else {
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                        serverInfo = null,
                        message = it.message.ifBlank { store.text(R.string.priv_ui_ready) },
                    )
                }
            }
        } catch (_: Throwable) {
            store.updateState {
                it.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    serverInfo = null,
                    message = store.text(R.string.priv_ui_ready),
                )
            }
        }
    }

    fun installRuntimeWatchers() {
        store.serverConnectedListener?.close()
        store.serverConnectedListener = PrivilegeRuntime.addServerConnectedListener(::connectServer)
        store.serverDisconnectedWatcher?.close()
        store.serverDisconnectedWatcher = PrivilegeRuntime.addServerDisconnectedListener {
            handleServerDisconnected()
        }
    }

    fun runServerStart(
        message: String,
        startupSource: String? = null,
        start: () -> PrivilegeServerInfo,
    ) {
        if (store.state.value.busy) return
        store.clearStartupLog()
        store.updateState {
            it.copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serverInfo = null,
                message = message,
            )
        }
        appendStartupSource(startupSource)
        store.appendStartupLog(message)
        store.executor.execute {
            try {
                connectServer(start())
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable)
            }
        }
    }

    fun runServerStartRequest(
        message: String,
        startedMessage: String,
        startupSource: String? = null,
        start: () -> Unit,
    ) {
        if (store.state.value.busy) return
        store.clearStartupLog()
        store.updateState {
            it.copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serverInfo = null,
                message = message,
            )
        }
        appendStartupSource(startupSource)
        store.appendStartupLog(message)
        store.executor.execute {
            try {
                start()
                store.updateState {
                    it.copy(
                        busy = false,
                        runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                        message = startedMessage,
                    )
                }
                store.appendStartupLog(startedMessage)
            } catch (throwable: Throwable) {
                setRuntimeFailure(throwable)
            }
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
                message = message,
            )
        }
        store.appendLog(message)
        store.executor.execute {
            try {
                val result = action()
                val resultMessage = onSuccess(result)
                store.updateState {
                    it.copy(
                        busy = false,
                        message = store.idleMessage(it),
                    )
                }
                store.appendLog(resultMessage)
            } catch (throwable: Throwable) {
                onFailure?.invoke(throwable)
                store.updateState {
                    it.copy(
                        busy = false,
                        message = throwable.failureMessage(),
                    )
                }
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

    private fun connectServer(serverInfo: PrivilegeServerInfo) {
        val shouldAppendLog = store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                serverInfo = serverInfo,
                message = store.text(R.string.priv_ui_connected),
                connectionSerial = it.connectionSerial + 1L,
            )
        }
        if (shouldAppendLog) {
            store.appendStartupLog(store.text(R.string.priv_ui_connected))
        }
    }

    private fun setRuntimeFailure(throwable: Throwable) {
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
                serverInfo = null,
                message = throwable.failureMessage(),
            )
        }
        store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
    }

    private fun handleServerDisconnected() {
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                serverInfo = null,
                message = store.text(R.string.priv_ui_binder_died),
            )
        }
        store.appendLog(store.text(R.string.priv_ui_binder_died))
    }
}
