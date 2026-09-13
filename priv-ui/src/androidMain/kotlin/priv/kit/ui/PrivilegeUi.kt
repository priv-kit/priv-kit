package priv.kit.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.runtime.PrivilegeUiDesiredEnabledStore
import priv.kit.ui.runtime.PrivilegeUiSilentStartRunner
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartMethodStore
import kotlin.time.Duration.Companion.milliseconds

public object PrivilegeUi {
    private val desiredEnabledLock = Any()
    private val desiredEnabledStore by lazy {
        PrivilegeUiDesiredEnabledStore(PrivilegeContext.require())
    }
    private val mutableDesiredEnabled by lazy {
        MutableStateFlow(desiredEnabledStore.read())
    }

    /**
     * Whether the user still wants automatic recovery of the Privileged Server.
     *
     * A matching successful foreground start enables this value. A confirmed stop or the
     * built-in disable action clears it. Disconnection, server death, and failed silent recovery
     * leave it unchanged. This state does not indicate whether a server is currently connected;
     * observe [Privilege.serverState] for the process-wide connection state. Its initial value is
     * read synchronously from app-private storage on first access.
     */
    public val desiredEnabled: StateFlow<Boolean> by lazy {
        mutableDesiredEnabled.asStateFlow()
    }

    internal fun setDesiredEnabled(enabled: Boolean) {
        synchronized(desiredEnabledLock) {
            desiredEnabledStore.write(enabled)
            mutableDesiredEnabled.value = enabled
        }
    }

    /**
     * Replays the last successful foreground startup method with the supplied current config.
     *
     * By default, replay runs only when a previous accepted server launch has enabled automatic
     * recovery. A missing or invalid setting is treated as disabled. Pass
     * [ignoreAutomaticRecoverySetting] only when the application intentionally needs to replay
     * regardless of that user setting.
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
        config: PrivilegeUiConfig,
        ignoreAutomaticRecoverySetting: Boolean = false,
    ): PrivilegeServerInfo? = withContext(Dispatchers.IO) {
        val applicationContext = PrivilegeContext.require()
        if (!ignoreAutomaticRecoverySetting) {
            val automaticRecoveryEnabled = runCatching {
                PrivilegeUiDesiredEnabledStore(applicationContext).read()
            }.getOrDefault(false)
            if (!automaticRecoveryEnabled) return@withContext null
        }
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
