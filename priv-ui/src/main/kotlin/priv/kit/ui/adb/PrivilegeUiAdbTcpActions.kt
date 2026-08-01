package priv.kit.ui.adb

import kotlinx.coroutines.CancellationException
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.core.adb.PrivilegeAdbAuthorizationRequestResult
import priv.kit.core.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbManager
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiSystemPromptCoordinator
import priv.kit.ui.R
import priv.kit.ui.privilegeUiTcpAuthorizationPrompt
import priv.kit.ui.privilegeUiWirelessDebuggingPrompt
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartSession
import priv.kit.ui.state.PrivilegeUiFailureKind
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiTcpAuthorizationFailureKind
import priv.kit.ui.state.toPrivilegeUiDiagnosticString

internal class PrivilegeUiAdbTcpActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val refreshTcpModeEnabled: () -> Unit,
    private val systemPromptCoordinator: PrivilegeUiSystemPromptCoordinator =
        PrivilegeUiSystemPromptCoordinator(),
    private val tcpAuthorizationRequester: suspend (
        tcpPort: Int,
        timeoutMillis: Long,
    ) -> PrivilegeAdbAuthorizationRequestResult = { tcpPort, timeoutMillis ->
        Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        ).requestTcpAuthorization(
            tcpPort = tcpPort,
            timeoutMillis = timeoutMillis,
        )
    },
    private val tcpModeStopper: suspend (tcpPort: Int) -> Unit = { tcpPort ->
        Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        ).stopTcp(tcpPort)
        Unit
    },
    private val wirelessTcpModeStopper: suspend (
        tcpPort: Int,
        options: PrivilegeAdbStartOptions,
    ) -> Unit = { tcpPort, options ->
        Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        ).stopTcp(
            tcpPort = tcpPort,
            options = options,
        )
        Unit
    },
    private val tcpModeRestarter: suspend (tcpPort: Int) -> Int = { tcpPort ->
        Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        ).restartTcp(tcpPort).port
    },
    private val wirelessTcpModeRestarter: suspend (
        tcpPort: Int,
        options: PrivilegeAdbStartOptions,
    ) -> Int = { tcpPort, options ->
        Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        ).restartTcp(
            tcpPort = tcpPort,
            options = options,
        ).port
    },
) {
    fun enableTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.config.tcpPort
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_enabling),
            failureKind = PrivilegeUiFailureKind.TCP_ENABLE_FAILED,
            onFailure = {
                store.updateTcpModePort(null)
            },
            action = {
                val manager = Privilege.createAdbManager(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                )
                manager.switchToTcp(
                    tcpPort = tcpPort,
                )
            },
            onSuccess = { result ->
                store.updateTcpModePort(result.port)
                store.updateConfiguredTcpModePort(result.port)
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_enabled)
            },
        )
    }

    fun disableTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.state.value.tcpModePort ?: return
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_disabling),
            failureKind = PrivilegeUiFailureKind.TCP_DISABLE_FAILED,
            onFailure = {
                refreshTcpModeEnabled()
            },
            action = {
                runTcpControlWithWirelessFallback(
                    staticAction = {
                        tcpModeStopper(tcpPort)
                    },
                    wirelessAction = { options ->
                        wirelessTcpModeStopper(tcpPort, options)
                    },
                )
            },
            onSuccess = {
                store.updateTcpModePort(null)
                store.updateConfiguredTcpModePort(null)
                store.updateState { current ->
                    current.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                    )
                }
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_disabled)
            },
        )
    }

    fun restartTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.state.value.tcpModePort ?: return
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_restarting),
            failureKind = PrivilegeUiFailureKind.TCP_RESTART_FAILED,
            onFailure = {
                refreshTcpModeEnabled()
            },
            action = {
                runTcpControlWithWirelessFallback(
                    staticAction = {
                        tcpModeRestarter(tcpPort)
                    },
                    wirelessAction = { options ->
                        wirelessTcpModeRestarter(tcpPort, options)
                    },
                )
            },
            onSuccess = { restartedPort ->
                store.updateTcpModePort(restartedPort)
                store.updateConfiguredTcpModePort(restartedPort)
                store.updateState { current ->
                    current.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                    )
                }
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_restarted)
            },
        )
    }

    private suspend fun <T> runTcpControlWithWirelessFallback(
        staticAction: suspend () -> T,
        wirelessAction: suspend (PrivilegeAdbStartOptions) -> T,
    ): T {
        val staticFailure = try {
            return staticAction()
        } catch (exception: CancellationException) {
            throw exception
        } catch (throwable: Throwable) {
            throwable
        }
        if (!isPrivilegeUiWirelessAdbSupported()) throw staticFailure
        store.appendLog(store.text(R.string.priv_ui_tcp_wireless_fallback))
        store.appendLog(staticFailure.toPrivilegeUiDiagnosticString())
        val options = privilegeUiStaticTcpSwitchOptions(
            managedWirelessAdbEnabled = store.managedWirelessAdbEnabledForStart(),
            managedWirelessAdbStatus = store.state.value.managedWirelessAdbStatus,
        )
        return try {
            withManagedWirelessDebuggingPrompt(options) {
                wirelessAction(options)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (wirelessFailure: Throwable) {
            wirelessFailure.addSuppressed(staticFailure)
            throw wirelessFailure
        }
    }

    private suspend fun <T> withManagedWirelessDebuggingPrompt(
        options: PrivilegeAdbStartOptions,
        action: suspend () -> T,
    ): T = if (
        options.port == null &&
        options.wirelessDebuggingControl != PrivilegeAdbWirelessDebuggingControl.NEVER
    ) {
        systemPromptCoordinator.withPrompt(
            prompt = privilegeUiWirelessDebuggingPrompt(),
            action = action,
        )
    } else {
        action()
    }

    suspend fun requestTcpAuthorizationForStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int,
    ): Boolean {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return false
        session.appendStartupLog(store.text(R.string.priv_ui_adb_static_authorize_action))
        session.appendStartupLog(store.text(R.string.priv_ui_tcp_authorization_prompt_waiting_help))
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                tcpAuthorizationFailureDialogText = null,
            )
        }
        val promptObservation = try {
            systemPromptCoordinator.withPromptObservation(privilegeUiTcpAuthorizationPrompt()) {
                tcpAuthorizationRequester(
                    tcpPort,
                    store.config.adbAuthorizationTimeoutMillis,
                )
            }
        } catch (exception: CancellationException) {
            markTcpAuthorizationUnauthorizedIfAuthorizing()
            throw exception
        }
        session.checkActive()
        return applyTcpAuthorizationResult(
            session = session,
            result = promptObservation.value,
            showFailure = session.showAttemptFeedback && !session.cancellationRequested,
            systemPromptObserved = promptObservation.hostTransitionObserved,
        )
    }

    fun tcpAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            progressText = store.resourceText(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
        ) {
            val manager = Privilege.createAdbManager(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            val activeTcpPort = requireStaticTcpReady(manager, this)
            startTcpAdbNow(activeTcpPort, this)
        }
    }

    suspend fun prepareStaticTcpForStart(
        tcpPort: Int,
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeUiStaticTcpPreparationResult {
        val manager = Privilege.createAdbManager(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val initialAuthorization = manager.prepareTcpForStart(tcpPort = tcpPort)
        val configuredTcpPort = manager.getConfiguredTcpPort()
        val activeTcpPort = manager.getActiveTcpPort()
        val authorization = when {
            configuredTcpPort == null -> null
            configuredTcpPort == tcpPort -> initialAuthorization
            else -> manager.prepareTcpForStart(tcpPort = configuredTcpPort)
        }
        val authorizationStatus = authorization
            ?.status
            ?.toUiTcpAuthorizationStatus()
            ?: PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN
        store.updateTcpModePort(activeTcpPort)
        store.updateConfiguredTcpModePort(configuredTcpPort)
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = authorizationStatus,
            )
        }
        authorization
            ?.failureMessage
            ?.takeIf { it.startsWith("Failed to prepare ADB for static TCP") }
            ?.let(session::appendStartupLog)
        return PrivilegeUiStaticTcpPreparationResult(
            tcpPort = configuredTcpPort,
            authorizationStatus = authorizationStatus,
        )
    }

    suspend fun requireStaticTcpReady(
        manager: PrivilegeAdbManager,
        session: PrivilegeUiRuntimeStartSession,
    ): Int {
        val configuredTcpPort = manager.getConfiguredTcpPort()
        store.updateConfiguredTcpModePort(configuredTcpPort)
        if (configuredTcpPort == null) {
            store.updateTcpModePort(null)
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            throwStaticTcpStartFailed(session)
        }
        val authorization = manager.prepareTcpForStart(tcpPort = configuredTcpPort)
        val activeTcpPort = manager.getActiveTcpPort()
        store.updateTcpModePort(activeTcpPort)
        store.updateState {
            it.copy(tcpAuthorizationStatus = authorization.status.toUiTcpAuthorizationStatus())
        }
        if (
            activeTcpPort != null &&
            authorization.status == PrivilegeAdbAuthorizationStatus.AUTHORIZED
        ) {
            return activeTcpPort
        }
        authorization.failureMessage
            ?.takeIf { it.isNotBlank() }
            ?.let(session::appendStartupLog)
        throwStaticTcpStartFailed(session)
    }

    suspend fun startTcpAdbNow(
        tcpPort: Int,
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeServerInfo {
        val serverInfo = PrivilegeRuntimeStartCoordinator.startAdb(
            launch = session.requireRuntimeClientLaunch(),
            options = PrivilegeAdbStartOptions(
                port = tcpPort,
            ),
            timeoutMillis = store.config.startTimeoutMillis,
            adbDeviceName = store.currentAdbDeviceNameOverride(),
            startupLogListener = session.startupLogListener,
        )
        store.updateTcpModePort(tcpPort)
        store.updateConfiguredTcpModePort(tcpPort)
        return serverInfo
    }

    private fun throwStaticTcpStartFailed(session: PrivilegeUiRuntimeStartSession): Nothing {
        val message = store.text(R.string.priv_ui_adb_static_start_failed)
        session.appendStartupLog(message)
        throw IllegalStateException(message)
    }

    private fun applyTcpAuthorizationResult(
        session: PrivilegeUiRuntimeStartSession,
        result: PrivilegeAdbAuthorizationRequestResult,
        showFailure: Boolean,
        systemPromptObserved: Boolean,
    ): Boolean {
        val status = if (result.authorized) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        } else {
            result.endReason.toUiTcpAuthorizationStatus()
        }
        store.updateState { current ->
            current.copy(tcpAuthorizationStatus = status)
        }
        if (result.authorized) {
            session.appendStartupLog(store.text(R.string.priv_ui_tcp_authorization_allowed))
        } else {
            val failureKind = privilegeUiTcpAuthorizationFailureKind(result.endReason)
            result.failureMessage
                ?.takeIf { it.isNotBlank() }
                ?.let(session::appendStartupLog)
            if (showFailure) {
                if (
                    result.endReason == PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT &&
                    !systemPromptObserved
                ) {
                    val promptNotShown = store.resourceText(
                        R.string.priv_ui_tcp_authorization_prompt_not_shown,
                    )
                    store.updateState { current ->
                        current.copy(tcpAuthorizationFailureDialogText = promptNotShown)
                    }
                    session.appendStartupLog(store.resolveText(promptNotShown))
                } else {
                    store.showFailure(failureKind)
                    session.appendStartupLog(store.text(failureKind.messageResId))
                }
            }
        }
        return result.authorized
    }

    private fun markTcpAuthorizationUnauthorizedIfAuthorizing() {
        store.updateState { current ->
            if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                current.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED)
            } else {
                current
            }
        }
    }
}

internal data class PrivilegeUiStaticTcpPreparationResult(
    val tcpPort: Int?,
    val authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
)

private fun PrivilegeAdbAuthorizationEndReason?.toUiTcpAuthorizationStatus(): PrivilegeUiAdbTcpAuthorizationStatus =
    when (this) {
        PrivilegeAdbAuthorizationEndReason.FAILED -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
        PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
        null,
        -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
    }
