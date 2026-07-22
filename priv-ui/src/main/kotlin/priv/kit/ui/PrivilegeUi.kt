package priv.kit.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.runtime.PrivilegeUiDesiredEnabledStore
import priv.kit.ui.runtime.PrivilegeUiSilentStartRunner
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartMethodStore
import kotlin.time.Duration.Companion.milliseconds

public object PrivilegeUi {
    /**
     * Runs [startSilently] only when a previous accepted server launch has enabled recovery.
     *
     * A missing or invalid preference file is treated as disabled. Failures do not clear the
     * persisted preference, so the built-in UI can explain the disconnected recovery state.
     */
    public suspend fun startSilentlyIfEnabled(
        context: Context,
        config: PrivilegeUiConfig,
    ): PrivilegeServerInfo? {
        val applicationContext = context.applicationContext
        val desiredEnabled = runCatching {
            PrivilegeUiDesiredEnabledStore(applicationContext).read()
        }.getOrDefault(false)
        if (!desiredEnabled) return null
        return startSilently(applicationContext, config)
    }

    /**
     * Replays the last successful foreground startup method with the supplied current config.
     *
     * This function does not create UI, fall back to another method, invoke Android permission
     * launchers, pair Wireless ADB, submit an ADB public key for authorization, or call an external
     * provider's authorization request. When no server is already connected, a missing, disabled,
     * unavailable, or unauthorized method returns `null`. A root manager remains responsible for
     * any UI caused by an expired Root grant. Foreground and silent startup are mutually exclusive;
     * this function returns `null` when foreground startup owns the process-local gate. Existing
     * built-in UI remains disabled until a silent attempt releases the gate and runtime state has
     * been refreshed. A multi-process app must call this function from only its designated Priv Kit
     * process.
     */
    public suspend fun startSilently(
        context: Context,
        config: PrivilegeUiConfig,
    ): PrivilegeServerInfo? = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val permit = PrivilegeUiStartGate.tryAcquireSilent() ?: return@withContext null
        permit.use {
            connectedOrReadyServerOrNull()?.let { return@withContext it }
            val preflight = PrivilegeRuntimeStartCoordinator.beginPreflight()
            if (preflight.remainingReconnectGraceMillis > 0L) {
                delay(preflight.remainingReconnectGraceMillis.milliseconds)
            }
            connectedOrReadyServerOrNull()?.let { return@withContext it }
            val method = try {
                PrivilegeUiStartMethodStore(applicationContext).read()
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val runtimeStartLease =
                PrivilegeRuntimeStartCoordinator.tryCommitClientStart(preflight)
                    ?: return@withContext connectedOrReadyServerOrNull()
            try {
                runtimeStartLease.use {
                    val launch = PrivilegeRuntimeStartCoordinator.beginClientLaunch(runtimeStartLease)
                        ?: return@withContext connectedOrReadyServerOrNull()
                    PrivilegeUiSilentStartRunner(
                        context = applicationContext,
                        config = config,
                    ).start(method, launch)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun connectedOrReadyServerOrNull(): PrivilegeServerInfo? =
        try {
            when {
                Privilege.pingServer() -> Privilege.getServerInfo()
                else -> Privilege.connectReadyServer()
            }
        } catch (_: Exception) {
            null
        }
}
