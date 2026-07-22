package priv.kit.ui.adb

import kotlinx.coroutines.CancellationException
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.core.adb.PrivilegeAdbAuthorizationRequestResult
import priv.kit.core.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbStarter
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.R
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartSession
import priv.kit.ui.state.PrivilegeUiFailureKind
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.privilegeUiTcpAuthorizationFailureKind

internal class PrivilegeUiAdbTcpActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val refreshTcpModeEnabled: () -> Unit,
    private val tcpAuthorizationRequester: (suspend (
        tcpPort: Int,
        timeoutMillis: Long,
    ) -> PrivilegeAdbAuthorizationRequestResult)? = null,
    private val beforeTcpAuthorizationResultCommit: () -> Unit = {},
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
                val starter = Privilege.createAdbStarter(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                )
                starter.switchToTcp(
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

    suspend fun requestTcpAuthorizationForStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int,
    ): Boolean {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return false
        session.appendStartupLog(store.text(R.string.priv_ui_adb_static_authorize_action))
        store.updateState {
            it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING)
        }
        val result = try {
            tcpAuthorizationRequester?.invoke(
                tcpPort,
                store.config.adbAuthorizationTimeoutMillis,
            ) ?: Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            ).requestTcpAuthorization(
                tcpPort = tcpPort,
                timeoutMillis = store.config.adbAuthorizationTimeoutMillis,
            )
        } catch (exception: CancellationException) {
            markTcpAuthorizationUnauthorizedIfAuthorizing()
            throw exception
        }
        session.checkActive()
        beforeTcpAuthorizationResultCommit()
        return applyTcpAuthorizationResult(
            session = session,
            result = result,
            showFailure = session.showAttemptFeedback && !session.cancellationRequested,
        )
    }

    fun tcpAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            progressText = store.resourceText(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
        ) {
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            val activeTcpPort = requireStaticTcpReady(starter, this)
            startTcpAdbNow(activeTcpPort, this)
        }
    }

    suspend fun prepareStaticTcpForStart(
        tcpPort: Int,
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeUiStaticTcpPreparationResult {
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val initialAuthorization = starter.prepareTcpForStart(tcpPort = tcpPort)
        val configuredTcpPort = starter.getConfiguredTcpPort()
        val activeTcpPort = starter.getActiveTcpPort()
        val authorization = when {
            configuredTcpPort == null -> null
            configuredTcpPort == tcpPort -> initialAuthorization
            else -> starter.prepareTcpForStart(tcpPort = configuredTcpPort)
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
        starter: PrivilegeAdbStarter,
        session: PrivilegeUiRuntimeStartSession,
    ): Int {
        val configuredTcpPort = starter.getConfiguredTcpPort()
        store.updateConfiguredTcpModePort(configuredTcpPort)
        if (configuredTcpPort == null) {
            store.updateTcpModePort(null)
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            throwStaticTcpStartFailed(session)
        }
        val authorization = starter.prepareTcpForStart(tcpPort = configuredTcpPort)
        val activeTcpPort = starter.getActiveTcpPort()
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
                discoverPort = false,
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
                store.showFailure(failureKind)
                session.appendStartupLog(store.text(failureKind.messageResId))
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
