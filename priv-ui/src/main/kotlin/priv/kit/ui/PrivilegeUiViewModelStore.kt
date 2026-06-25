package priv.kit.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiViewModelStore : AutoCloseable {
    val state = MutableStateFlow(PrivilegeUiState())
    val tcpModeEnabled = MutableStateFlow(false)
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var applicationContext: Context? = null
    var config: PrivilegeUiConfig = PrivilegeUiConfig()
    var attached: Boolean = false
    var serverConnectedListener: Closeable? = null
    var serverDisconnectedWatcher: Closeable? = null
    var pairingEventReceiver: android.content.BroadcastReceiver? = null
    var startNotificationPairingAfterPermission: Boolean = false
    var wirelessStatusPollingStop: AtomicBoolean? = null
    var wirelessStatusPollingThread: Thread? = null
    var externalStartStatusPollingStop: AtomicBoolean? = null
    var externalStartStatusPollingThread: Thread? = null
    val wirelessStatusRefreshRunning = AtomicBoolean(false)
    val tcpModeRefreshRunning = AtomicBoolean(false)
    val externalStartStatusRefreshRunning = AtomicBoolean(false)

    fun initializeState(config: PrivilegeUiConfig) {
        val context = requireContext()
        val modes = config.effectiveStartupModes()
        val selected = state.value.selectedStartupMode.takeIf { it in modes } ?: modes.first()
        updateState { current ->
            current.copy(
                selectedStartupMode = selected,
                startupModes = modes,
                message = current.message.ifBlank { context.getString(R.string.priv_ui_ready) },
                pairingMessage = current.pairingMessage.ifBlank {
                    context.getString(R.string.priv_ui_pairing_default_message)
                },
                notificationPairingRunning = PrivilegeAdbPairingService.running.value,
                externalStartItems = config.externalStartProviders.map { provider ->
                    PrivilegeUiExternalStartItemState(
                        id = provider.id,
                        label = provider.label,
                    )
                },
            )
        }
    }

    fun updateState(transform: (PrivilegeUiState) -> PrivilegeUiState) {
        state.update(transform)
    }

    fun updateExternalStartItem(
        id: String,
        transform: (PrivilegeUiExternalStartItemState) -> PrivilegeUiExternalStartItemState,
    ) {
        updateState { current ->
            current.copy(
                externalStartItems = current.externalStartItems.map { item ->
                    if (item.id == id) transform(item) else item
                },
            )
        }
    }

    fun appendLog(line: String) {
        if (line.isBlank()) return
        // Intentionally kept as a sink; the default UI does not expose diagnostics.
    }

    fun requireContext(): Context =
        applicationContext ?: error("PrivilegeUiViewModel.attach(context, config) must be called first")

    fun text(id: Int, vararg args: Any): String =
        requireContext().getString(id, *args)

    fun currentAdbDeviceNameOverride(): String? =
        config.adbDeviceName
            ?.toPrivilegeUiAdbDeviceNameText()
            ?.ifBlank { null }

    override fun close() {
        executor.shutdownNow()
    }

    fun idleMessage(state: PrivilegeUiState = this.state.value): String =
        when (state.runtimeStatus) {
            PrivilegeUiRuntimeStatus.CONNECTED -> text(R.string.priv_ui_connected)
            PrivilegeUiRuntimeStatus.DISCONNECTED,
            PrivilegeUiRuntimeStatus.FAILED,
            -> text(R.string.priv_ui_ready)
            PrivilegeUiRuntimeStatus.STARTING -> state.message
        }

    private fun PrivilegeUiConfig.effectiveStartupModes(): List<PrivilegeUiStartupMode> {
        val modes = startupModes
            .filterTo(mutableSetOf()) { it in USER_VISIBLE_AUTHORIZATION_MODE_ORDER }
        if (externalStartProviders.isNotEmpty()) modes += PrivilegeUiStartupMode.EXTERNAL
        if (modes.isEmpty()) modes += PrivilegeUiStartupMode.ROOT
        return USER_VISIBLE_AUTHORIZATION_MODE_ORDER.filter { it in modes }
    }

    private companion object {
        val USER_VISIBLE_AUTHORIZATION_MODE_ORDER = listOf(
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.EXTERNAL,
            PrivilegeUiStartupMode.MANUAL_SHELL,
            PrivilegeUiStartupMode.ROOT,
        )
    }
}
