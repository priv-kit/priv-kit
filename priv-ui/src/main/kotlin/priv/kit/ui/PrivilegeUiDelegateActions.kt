package priv.kit.ui

import priv.kit.runtime.PrivilegeRuntime
import java.io.Closeable

internal class PrivilegeUiDelegateActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
) {
    fun refreshDelegateStatus(providerId: String? = null) {
        val context = store.requireContext()
        val providers = store.config.delegateProviders.filter { providerId == null || it.id == providerId }
        providers.forEach { provider ->
            val snapshot = runCatching { provider.snapshot(context) }
                .getOrElse { throwable ->
                    PrivilegeUiDelegateSnapshot(
                        message = throwable.failureMessage(),
                        exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                    )
                }
            store.updateDelegateItem(provider.id) {
                it.copy(snapshot = snapshot, busy = false)
            }
            if (snapshot.exceptionText.isNotBlank()) store.appendLog(snapshot.exceptionText)
        }
    }

    fun authorizeOrStartDelegate(providerId: String) {
        if (store.state.value.busy) return
        val context = store.requireContext()
        val provider = store.config.delegateProviders.firstOrNull { it.id == providerId } ?: return
        val snapshot = runCatching { provider.snapshot(context) }
            .getOrElse { throwable ->
                PrivilegeUiDelegateSnapshot(
                    message = throwable.failureMessage(),
                    exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                )
            }
        if (!snapshot.canStart) {
            val requested = runCatching { provider.requestAuthorization(context) }
                .getOrElse { throwable ->
                    PrivilegeUiDelegateSnapshot(
                        message = throwable.failureMessage(),
                        exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                    )
                }
            store.updateDelegateItem(provider.id) { it.copy(snapshot = requested) }
            if (!requested.canStart) {
                if (requested.message.isNotBlank()) store.appendLog(requested.message.toString())
                if (requested.exceptionText.isNotBlank()) store.appendLog(requested.exceptionText)
                return
            }
        }

        runtimeActions.runServerStart(store.text(R.string.priv_ui_delegate_starting)) {
            val delegateExecutor = provider.createExecutor(context)
            try {
                PrivilegeRuntime.startDelegate(
                    executor = delegateExecutor,
                    timeoutMillis = store.config.startTimeoutMillis,
                    followDeathDelayMillis = store.config.followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = store.config.activeReconnectOnOwnerDeath,
                )
            } finally {
                (delegateExecutor as? Closeable)?.close()
            }
        }
    }
}
