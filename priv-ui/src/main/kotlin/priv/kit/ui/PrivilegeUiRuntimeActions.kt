package priv.kit.ui

import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeRuntime

internal class PrivilegeUiRuntimeActions(
    private val store: PrivilegeUiViewModelStore,
) : AutoCloseable {
    fun configureOwnerDeathBehavior() {
        PrivilegeRuntime.configureOwnerDeathBehavior(
            followDeathDelayMillis = store.config.followDeathDelayMillis,
            activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
        )
    }

    fun startRoot() {
        runServerStart(store.text(R.string.priv_ui_starting_root)) {
            PrivilegeRuntime.startRoot(
                timeoutMillis = store.config.startTimeoutMillis,
                followDeathDelayMillis = store.config.followDeathDelayMillis,
                activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
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
        store.readyServerWatcher?.close()
        store.readyServerWatcher = PrivilegeRuntime.watchReadyServers(
            onReady = ::connectServer,
            onFailure = { throwable -> store.appendLog(throwable.toPrivilegeUiDiagnosticString()) },
            followDeathDelayMillis = store.config.followDeathDelayMillis,
            activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
        )
        store.serverDisconnectedWatcher?.close()
        store.serverDisconnectedWatcher = PrivilegeRuntime.addServerDisconnectedListener {
            handleServerDisconnected()
        }
    }

    fun runServerStart(
        message: String,
        start: () -> PrivilegeServerInfo,
    ) {
        if (store.state.value.busy) return
        store.updateState {
            it.copy(
                busy = true,
                runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
                serverInfo = null,
                message = message,
            )
        }
        store.appendLog(message)
        store.executor.execute {
            try {
                connectServer(start())
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
        store.readyServerWatcher?.close()
        store.serverDisconnectedWatcher?.close()
        store.readyServerWatcher = null
        store.serverDisconnectedWatcher = null
    }

    private fun connectServer(serverInfo: PrivilegeServerInfo) {
        store.updateState {
            it.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                serverInfo = serverInfo,
                message = store.text(R.string.priv_ui_connected),
                connectionSerial = it.connectionSerial + 1L,
            )
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
        store.appendLog(throwable.toPrivilegeUiDiagnosticString())
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
