package priv.kit.ui.external

import priv.kit.ui.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import priv.kit.core.internal.runtime.PrivilegeRuntimeClientLaunch
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiExternalStartActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val createNativeStarterCommand: (PrivilegeRuntimeClientLaunch) -> String =
        PrivilegeRuntimeStartCoordinator::createNativeStarterCommand,
    private val acquireInteractivePermit: () -> AutoCloseable? =
        PrivilegeUiStartGate.newInteractivePermitAcquirer(),
) {
    private val statusRefresh = Mutex()

    suspend fun pollExternalStartStatus() {
        if (store.config.externalStartProviders.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            refreshExternalStartStatusNow(providerId = null)
            delay(store.config.externalStartStatusPollIntervalMillis.milliseconds)
        }
    }

    suspend fun refreshExternalStartStatusNow(
        providerId: String?,
    ): Boolean = statusRefresh.withLock {
        refreshExternalStartStatusOnce(providerId)
        true
    }

    private suspend fun refreshExternalStartStatusOnce(providerId: String?) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        val providers = store.config.externalStartProviders.filter {
            providerId == null || it.id == providerId
        }
        if (providers.isEmpty()) return
        val context = store.applicationContext ?: return
        providers.forEach { provider ->
            val snapshot = provider.snapshotOrFailure(context)
            store.updateExternalStartItem(provider.id) {
                it.copy(snapshot = snapshot)
            }
            if (snapshot.exceptionText.isNotBlank()) store.appendLog(snapshot.exceptionText)
        }
    }

    suspend fun authorizeOrStartExternal(providerId: String) {
        if (store.state.value.busy) return
        val interactionPermit = acquireInteractivePermit() ?: return
        try {
            if (store.state.value.busy) return
            val context = store.requireContext()
            val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return
            val snapshot = provider.snapshotOrFailure(context)
            if (!snapshot.canStart) {
                val requested = try {
                    provider.requestAuthorization(context)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (throwable: Throwable) {
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

            startExternal(provider, context)
        } finally {
            interactionPermit.close()
        }
    }

    suspend fun directStartAttempt(providerId: String): PrivilegeUiRuntimeStartAttempt.Request? {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return null
        val context = store.requireContext()
        val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return null
        val snapshot = provider.snapshotOrFailure(context)
        store.updateExternalStartItem(provider.id) { it.copy(snapshot = snapshot) }
        if (snapshot.exceptionText.isNotBlank()) store.appendLog(snapshot.exceptionText)
        if (!snapshot.canStart) return null
        return externalStartAttempt(provider, context)
    }

    private fun startExternal(
        provider: PrivilegeUiExternalStartProvider,
        context: Context,
    ): Boolean = runtimeActions.runServerStartRequest(externalStartAttempt(provider, context))

    private fun externalStartAttempt(
        provider: PrivilegeUiExternalStartProvider,
        context: Context,
    ): PrivilegeUiRuntimeStartAttempt.Request =
        PrivilegeUiRuntimeStartAttempt.Request(
            progressText = store.resourceText(R.string.priv_ui_external_starting),
            startedText = store.resourceText(R.string.priv_ui_external_start_requested),
            startupSource = provider.label.toString(),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
            runtimeStartProviderId = provider.id,
        ) {
            val commandLine = createNativeStarterCommand(requireRuntimeClientLaunch())
            if (provider is PrivilegeUiStreamingExternalStartProvider) {
                provider.start(
                    context = context,
                    commandLine = commandLine,
                    startupLogListener = startupLogListener,
                )
            } else {
                provider.start(context, commandLine)
            }
        }

    private suspend fun PrivilegeUiExternalStartProvider.snapshotOrFailure(
        context: Context,
    ): PrivilegeUiExternalStartSnapshot = try {
        snapshot(context)
    } catch (exception: CancellationException) {
        throw exception
    } catch (throwable: Throwable) {
        PrivilegeUiExternalStartSnapshot(
            message = throwable.failureMessage(),
            exceptionText = throwable.toPrivilegeUiDiagnosticString(),
        )
    }

}
