package priv.kit.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import priv.kit.PrivilegeServerInfo
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiDirectStartTarget
import priv.kit.ui.runtime.PrivilegeUiManualShellActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.directStartTargets
import priv.kit.ui.state.PrivilegeUiNoopCloseable
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.copyToClipboard
import priv.kit.ui.state.isPrivilegeUiWirelessAdbSupported
import priv.kit.ui.state.privilegeUiStaticTcpOpenCommand
import java.util.concurrent.atomic.AtomicBoolean

public open class PrivilegeUiViewModel @JvmOverloads public constructor(
    application: Application,
    public val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) : AndroidViewModel(application) {
    private val store = PrivilegeUiViewModelStore(application)
    private val runtimeActions = PrivilegeUiRuntimeActions(
        store = store,
        coroutineScope = viewModelScope,
    )
    private val manualShellActions = PrivilegeUiManualShellActions(store)
    private val adbActions = PrivilegeUiAdbActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
    )
    private val externalStartActions = PrivilegeUiExternalStartActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
    )
    private val ownerClosed = AtomicBoolean(false)
    private var wirelessStatusPollingHandle: AutoCloseable? = null
    private var tcpModeStatusPollingHandle: AutoCloseable? = null
    private var externalStartStatusPollingHandle: AutoCloseable? = null
    private var deliveredConnectionSerial = 0L
    private val permissionRequestChannel = Channel<PrivilegeUiPermissionRequest>(Channel.BUFFERED)
    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    internal val snackbarMessages: SharedFlow<String> = store.snackbarMessages
    internal val permissionRequests = permissionRequestChannel.receiveAsFlow()

    init {
        addCloseable { closeOwner() }
        configure(config)
    }

    private fun configure(config: PrivilegeUiConfig) {
        store.config = config

        adbActions.observePairingEvents()
        runtimeActions.installRuntimeWatchers()
        store.initializeState(config)

        runtimeActions.refreshRuntimeStatus()
        manualShellActions.loadCommand()
        externalStartActions.refreshExternalStartStatus()
        adbActions.refreshAdbIdentityInfo()
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    /** Return true when the host handled the back action; false uses the system back dispatcher. */
    protected open fun onBackClick(): Boolean = false

    /** Override together with [hasHelpAction] to handle the optional help action. */
    protected open fun onHelpClick(): Unit = Unit

    /** Controls whether the authorization page exposes its optional help action. */
    protected open val hasHelpAction: Boolean = false

    /** Called once for each connection serial while this ViewModel is alive. */
    protected open fun onConnected(serverInfo: PrivilegeServerInfo): Unit = Unit

    internal fun dispatchBackClick(): Boolean = onBackClick()

    internal fun dispatchHelpClick() {
        onHelpClick()
    }

    internal val helpActionVisible: Boolean
        get() = hasHelpAction

    internal fun dispatchConnected(
        connectionSerial: Long,
        serverInfo: PrivilegeServerInfo,
    ) {
        if (connectionSerial <= deliveredConnectionSerial) return
        deliveredConnectionSerial = connectionSerial
        onConnected(serverInfo)
    }

    public open fun updatePairingCode(value: String) {
        adbActions.updatePairingCode(value)
    }

    public open fun selectStartupMode(mode: PrivilegeUiStartupMode) {
        if (mode !in store.state.value.startupModes) return
        store.updateState { it.copy(selectedStartupMode = mode) }
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun startRoot() {
        runtimeActions.startRoot()
    }

    /** Starts the first available provider sequence without showing provider-specific prompts. */
    public open fun startAvailable() {
        startAvailableSilently()
    }

    private fun startAvailableSilently() {
        if (
            store.state.value.busy ||
            store.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
        ) {
            return
        }
        adbActions.refreshAdbStartPrerequisites()
        val directTargets = store.state.value.directStartTargets(
            tcpPolicy = store.config.adbTcpPolicy,
            wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
        )
        val attempts = directTargets.flatMap { target ->
            when (target) {
                PrivilegeUiDirectStartTarget.Adb -> adbActions.directStartAttempts()
                is PrivilegeUiDirectStartTarget.External -> {
                    listOfNotNull(externalStartActions.directStartAttempt(target.providerId))
                }
                PrivilegeUiDirectStartTarget.Root -> listOf(runtimeActions.rootStartAttempt())
            }
        }
        runtimeActions.runServerStartFallback(attempts)
    }

    public open fun stopServer() {
        runtimeActions.stopServer()
    }

    public open fun stopCurrentStart() {
        runtimeActions.stopCurrentStart()
    }

    public open fun copyManualCommand(context: Context) {
        manualShellActions.copyCommand(context)
    }

    public open fun copyStaticTcpCommand(context: Context) {
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_adb_static_command_clip_label),
            text = privilegeUiStaticTcpOpenCommand(store.config.tcpPort),
        )
    }

    public open fun copyStartupLog(context: Context) {
        val logText = store.state.value.startupLogLines.joinToString("\n")
        if (logText.isBlank()) return
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_startup_log_clip_label),
            text = logText,
        )
    }

    public open fun startNotificationPairing() {
        adbActions.startNotificationPairing(
            onNotificationPermissionRequired = ::requestNotificationPermission,
        )
    }

    public open fun stopNotificationPairing() {
        adbActions.stopNotificationPairing()
    }

    public open fun closePairingDialog() {
        adbActions.closePairingDialog()
    }

    public open fun submitNotificationPairingCode() {
        adbActions.submitNotificationPairingCode()
    }

    public open fun handleNotificationPermissionResult(granted: Boolean) {
        adbActions.handleNotificationPermissionResult(granted)
    }

    public open fun startWirelessAdb() {
        adbActions.startWirelessAdb(::requestLocalNetworkPermission)
    }

    public open fun startAdb() {
        adbActions.startAdb(::requestLocalNetworkPermission)
    }

    public open fun startWirelessAdbStatusPolling(): AutoCloseable =
        if (isPrivilegeUiWirelessAdbSupported()) {
            adbActions.startWirelessAdbStatusPolling()
        } else {
            adbActions.stopWirelessAdbStatusPolling()
            PrivilegeUiNoopCloseable
        }

    public open fun refreshWirelessAdbStatus() {
        if (isPrivilegeUiWirelessAdbSupported()) {
            adbActions.refreshWirelessAdbStatus()
        }
    }

    public open fun onHostResume() {
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            isPrivilegeUiWirelessAdbSupported()
        ) {
            adbActions.refreshWirelessAdbStatus()
        }
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
            externalStartActions.refreshExternalStartStatus()
        }
        refreshTcpModeEnabledIfSelected()
    }

    public open fun stopWirelessAdbStatusPolling() {
        wirelessStatusPollingHandle?.close()
        wirelessStatusPollingHandle = null
        adbActions.stopWirelessAdbStatusPolling()
    }

    public open fun startTcpModeStatusPolling(): AutoCloseable =
        adbActions.startTcpModeStatusPolling()

    public open fun stopTcpModeStatusPolling() {
        tcpModeStatusPollingHandle?.close()
        tcpModeStatusPollingHandle = null
        adbActions.stopTcpModeStatusPolling()
    }

    public open fun enableTcpMode() {
        adbActions.enableTcpMode()
    }

    public open fun refreshTcpModeEnabled() {
        adbActions.refreshTcpModeEnabled()
    }

    public open fun startStaticTcpAdb() {
        adbActions.startStaticTcpAdb(::requestLocalNetworkPermission)
    }

    public open fun refreshExternalStartStatus(providerId: String? = null) {
        externalStartActions.refreshExternalStartStatus(providerId)
    }

    public open fun startExternalStartStatusPolling(): AutoCloseable =
        externalStartActions.startExternalStartStatusPolling()

    public open fun stopExternalStartStatusPolling() {
        externalStartStatusPollingHandle?.close()
        externalStartStatusPollingHandle = null
        externalStartActions.stopExternalStartStatusPolling()
    }

    public open fun authorizeOrStartExternal(providerId: String) {
        externalStartActions.authorizeOrStartExternal(providerId)
    }

    private fun syncWirelessAdbStatusPolling() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            isPrivilegeUiWirelessAdbSupported()
        ) {
            if (wirelessStatusPollingHandle == null) {
                wirelessStatusPollingHandle = adbActions.startWirelessAdbStatusPolling()
            }
        } else {
            wirelessStatusPollingHandle?.close()
            wirelessStatusPollingHandle = null
        }
    }

    private fun syncTcpModeStatusPolling() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            if (tcpModeStatusPollingHandle == null) {
                tcpModeStatusPollingHandle = adbActions.startTcpModeStatusPolling()
            }
        } else {
            tcpModeStatusPollingHandle?.close()
            tcpModeStatusPollingHandle = null
        }
    }

    private fun syncExternalStartStatusPolling() {
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
            if (externalStartStatusPollingHandle == null) {
                externalStartStatusPollingHandle =
                    externalStartActions.startExternalStartStatusPolling()
            }
        } else {
            externalStartStatusPollingHandle?.close()
            externalStartStatusPollingHandle = null
        }
    }

    private fun refreshTcpModeEnabledIfSelected() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            adbActions.refreshTcpModeEnabled()
        }
    }

    private fun requestNotificationPermission() {
        permissionRequestChannel.trySend(PrivilegeUiPermissionRequest.Notification)
    }

    private fun requestLocalNetworkPermission(permission: String) {
        permissionRequestChannel.trySend(PrivilegeUiPermissionRequest.LocalNetwork(permission))
    }

    override fun onCleared() {
        closeOwner()
    }

    private fun closeOwner() {
        if (!ownerClosed.compareAndSet(false, true)) return
        runtimeActions.close()
        runCatching { externalStartActions.close() }
        runCatching { adbActions.close() }
        runCatching { store.close() }
        permissionRequestChannel.close()
    }
}

internal sealed interface PrivilegeUiPermissionRequest {
    data object Notification : PrivilegeUiPermissionRequest

    data class LocalNetwork(
        val permission: String,
    ) : PrivilegeUiPermissionRequest
}
