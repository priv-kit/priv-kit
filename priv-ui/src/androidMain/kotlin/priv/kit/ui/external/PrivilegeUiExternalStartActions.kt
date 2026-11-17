package priv.kit.ui.external

import priv.kit.ui.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeExternalStartResult
import priv.kit.core.internal.runtime.PrivilegeRuntimeClientLaunch
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiExternalStartActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val createNativeStarterCommand: suspend (PrivilegeRuntimeClientLaunch) -> String =
        PrivilegeRuntimeStartCoordinator::createNativeStarterCommand,
    private val acquireInteractivePermit: () -> AutoCloseable? =
        PrivilegeUiStartGate.newInteractivePermitAcquirer(),
    private val systemPromptCoordinator: PrivilegeUiSystemPromptCoordinator =
        PrivilegeUiSystemPromptCoordinator(),
) {
    private val statusRefreshes = store.config.externalStartProviders.associate {
        it.id to PrivilegeUiRefreshTask()
    }

    suspend fun pollExternalStartStatus() {
        if (store.config.externalStartProviders.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            delay(store.config.externalStartStatusPollIntervalMillis.milliseconds)
            refreshExternalStartStatusNow(providerId = null)
        }
    }

    suspend fun refreshExternalStartStatusNow(providerId: String?): Boolean {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return false
        val context = store.applicationContext ?: return false
        store.config.externalStartProviders.filter { providerId == null || it.id == providerId }
            .forEach { provider ->
                statusRefreshes.getValue(provider.id).run {
                    checkAuthorization(provider, context)
                }
            }
        return true
    }

    suspend fun authorizeOrStartExternal(
        providerId: String,
        replaceConnectedServer: Boolean = false,
    ) {
        if (store.state.value.busy) return
        val interactionPermit = acquireInteractivePermit() ?: return
        try {
            if (store.state.value.busy) return
            val context = store.requireContext()
            val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return
            val checked = readAuthorization(provider.id) { provider.checkAuthorization(context) }
            if (checked.getOrNull()?.authorized != true) {
                val requested = readAuthorization(provider.id) {
                    systemPromptCoordinator.withPrompt(
                        privilegeUiExternalAuthorizationPrompt(store.externalStartLabel(provider.id)),
                    ) {
                        provider.requestAuthorization(context)
                    }
                }
                if (requested.getOrNull()?.authorized != true) {
                    requested.onSuccess { result ->
                        result.message?.let { store.appendStartupLog(it.toString()) }
                    }.onFailure { throwable ->
                        store.appendStartupLog(throwable.failureMessage())
                        store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
                    }
                    return
                }
            }

            startExternal(provider, context, replaceConnectedServer)
        } finally {
            interactionPermit.close()
        }
    }

    suspend fun directStartAttempt(providerId: String): PrivilegeUiRuntimeStartAttempt.Request? {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return null
        val context = store.requireContext()
        val provider = store.config.externalStartProviders.firstOrNull { it.id == providerId } ?: return null
        if (checkAuthorization(provider, context) != true) return null
        return externalStartAttempt(provider, context)
    }

    private fun startExternal(
        provider: PrivilegeExternalStartProvider,
        context: Context,
        replaceConnectedServer: Boolean,
    ): Boolean = runtimeActions.runServerStartRequest(
        externalStartAttempt(provider, context),
        replaceConnectedServer,
    )

    private fun externalStartAttempt(
        provider: PrivilegeExternalStartProvider,
        context: Context,
    ): PrivilegeUiRuntimeStartAttempt.Request =
        PrivilegeUiRuntimeStartAttempt.Request(
            progressText = store.resourceText(R.string.priv_ui_external_starting),
            startedText = store.resourceText(R.string.priv_ui_external_start_requested),
            startupSource = store.externalStartLabel(provider.id).toString(),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
            runtimeStartProviderId = provider.id,
        ) {
            val commandLine = createNativeStarterCommand(requireRuntimeClientLaunch())
            provider.runStarter(
                context = context,
                commandLine = commandLine,
                startupLogListener = startupLogListener,
            )
        }

    private suspend fun checkAuthorization(
        provider: PrivilegeExternalStartProvider,
        context: Context,
    ): Boolean? = readAuthorization(provider.id) { provider.checkAuthorization(context) }
        .onFailure { store.appendStartupLog(it.toPrivilegeUiDiagnosticString()) }
        .getOrNull()?.authorized

    private suspend fun readAuthorization(
        providerId: String,
        read: suspend () -> PrivilegeExternalStartResult,
    ): Result<PrivilegeExternalStartResult> {
        val result = try {
            Result.success(read())
        } catch (exception: CancellationException) {
            throw exception
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
        store.setExternalStartAuthorization(providerId, result.getOrNull()?.authorized)
        return result
    }

}
