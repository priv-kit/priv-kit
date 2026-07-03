package priv.kit.ui

import android.content.Context
import priv.kit.Privilege
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiExternalStartActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val createShellStartCommand: () -> String = Privilege::createShellStartCommand,
) : AutoCloseable {
    fun refreshExternalStartStatus(providerId: String? = null) {
        Thread {
            refreshExternalStartStatusNow(stop = null, providerId = providerId)
        }.apply {
            name = "priv-ui-external-start-refresh"
            isDaemon = true
            start()
        }
    }

    fun startExternalStartStatusPolling() {
        if (store.config.externalStartProviders.isEmpty()) return
        synchronized(store) {
            if (store.externalStartStatusPollingThread?.isAlive == true) return
            val stop = AtomicBoolean(false)
            val thread = Thread {
                pollExternalStartStatus(stop)
            }.apply {
                name = "priv-ui-external-start-status"
                isDaemon = true
            }
            store.externalStartStatusPollingStop = stop
            store.externalStartStatusPollingThread = thread
            thread.start()
        }
    }

    fun stopExternalStartStatusPolling() {
        val thread: Thread?
        synchronized(store) {
            store.externalStartStatusPollingStop?.set(true)
            thread = store.externalStartStatusPollingThread
            store.externalStartStatusPollingStop = null
            store.externalStartStatusPollingThread = null
        }
        thread?.interrupt()
    }

    override fun close() {
        store.pendingExternalStartProviderId = null
        stopExternalStartStatusPolling()
    }

    private fun pollExternalStartStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            refreshExternalStartStatusNow(stop = stop, providerId = null)
            if (!sleepExternalStartStatusPolling(stop)) return
        }
    }

    internal fun refreshExternalStartStatusNow(
        stop: AtomicBoolean?,
        providerId: String?,
    ) {
        if (!store.externalStartStatusRefreshRunning.compareAndSet(false, true)) return
        try {
            if (stop?.get() == true) return
            refreshExternalStartStatusOnce(stop = stop, providerId = providerId)
        } finally {
            store.externalStartStatusRefreshRunning.set(false)
        }
    }

    private fun refreshExternalStartStatusOnce(
        stop: AtomicBoolean?,
        providerId: String?,
    ) {
        val providers = store.config.externalStartProviders.filter { providerId == null || it.id == providerId }
        if (providers.isEmpty()) return
        val context = store.applicationContext ?: return
        providers.forEach { provider ->
            if (stop?.get() == true) return
            val snapshot = runCatching { provider.snapshot(context) }
                .getOrElse { throwable ->
                    PrivilegeUiExternalStartSnapshot(
                        message = throwable.failureMessage(),
                        exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                    )
                }
            if (stop?.get() == true) return
            store.updateExternalStartItem(provider.id) {
                it.copy(snapshot = snapshot, busy = false)
            }
            if (snapshot.exceptionText.isNotBlank()) store.appendLog(snapshot.exceptionText)
            continuePendingExternalStart(
                provider = provider,
                snapshot = snapshot,
                context = context,
            )
        }
    }

    fun authorizeOrStartExternal(providerId: String) {
        if (store.state.value.busy) return
        val context = store.requireContext()
        val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return
        val snapshot = runCatching { provider.snapshot(context) }
            .getOrElse { throwable ->
                PrivilegeUiExternalStartSnapshot(
                    message = throwable.failureMessage(),
                    exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                )
            }
        if (!snapshot.canStart) {
            store.pendingExternalStartProviderId = provider.id
            val requested = runCatching { provider.requestAuthorization(context) }
                .getOrElse { throwable ->
                    store.pendingExternalStartProviderId = null
                    PrivilegeUiExternalStartSnapshot(
                        message = throwable.failureMessage(),
                        exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                    )
                }
            store.updateExternalStartItem(provider.id) { it.copy(snapshot = requested) }
            if (!requested.canStart) {
                if (requested.message.isNotBlank()) store.appendLog(requested.message.toString())
                if (requested.exceptionText.isNotBlank()) store.appendLog(requested.exceptionText)
                return
            }
        }

        store.pendingExternalStartProviderId = null
        startExternal(provider, context)
    }

    fun directStartAttempt(providerId: String): PrivilegeUiRuntimeStartAttempt.Request? {
        val context = store.requireContext()
        val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return null
        val snapshot = runCatching { provider.snapshot(context) }
            .getOrElse { throwable ->
                PrivilegeUiExternalStartSnapshot(
                    message = throwable.failureMessage(),
                    exceptionText = throwable.toPrivilegeUiDiagnosticString(),
                )
            }
        store.updateExternalStartItem(provider.id) { it.copy(snapshot = snapshot) }
        if (snapshot.exceptionText.isNotBlank()) store.appendLog(snapshot.exceptionText)
        if (!snapshot.canStart) return null
        return externalStartAttempt(provider, context)
    }

    private fun continuePendingExternalStart(
        provider: PrivilegeUiExternalStartProvider,
        snapshot: PrivilegeUiExternalStartSnapshot,
        context: Context,
    ) {
        if (
            store.pendingExternalStartProviderId == provider.id &&
            snapshot.canStart &&
            !store.state.value.busy
        ) {
            store.pendingExternalStartProviderId = null
            startExternal(provider, context)
        }
    }

    private fun startExternal(
        provider: PrivilegeUiExternalStartProvider,
        context: Context,
    ) {
        runtimeActions.runServerStartRequest(externalStartAttempt(provider, context))
    }

    private fun externalStartAttempt(
        provider: PrivilegeUiExternalStartProvider,
        context: Context,
    ): PrivilegeUiRuntimeStartAttempt.Request =
        PrivilegeUiRuntimeStartAttempt.Request(
            message = store.text(R.string.priv_ui_external_starting),
            startedMessage = store.text(R.string.priv_ui_external_start_requested),
            startupSource = provider.label.toString(),
        ) {
            val commandLine = createShellStartCommand()
            if (provider is PrivilegeUiStreamingExternalStartProvider) {
                provider.start(
                    context = context,
                    commandLine = commandLine,
                    startupLogListener = store.startupLogListener,
                )
            } else {
                provider.start(context, commandLine)
            }
        }

    private fun sleepExternalStartStatusPolling(stop: AtomicBoolean): Boolean =
        try {
            Thread.sleep(store.config.externalStartStatusPollIntervalMillis)
            !stop.get()
        } catch (_: InterruptedException) {
            false
        }
}
