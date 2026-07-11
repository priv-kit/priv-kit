package priv.kit.ui.state

import priv.kit.ui.*
import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import priv.kit.PrivilegeStartupLogLine
import priv.kit.PrivilegeStartupLogListener
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

internal class PrivilegeUiViewModelStore(
    context: Context? = null,
) : AutoCloseable {
    val state = MutableStateFlow(PrivilegeUiState())
    val tcpModeEnabled = MutableStateFlow(false)
    private val snackbarMessageState = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = snackbarMessageState.asSharedFlow()
    val startupLogListener = PrivilegeStartupLogListener { line ->
        appendStartupLog(line)
    }

    @Volatile
    var applicationContext: Context? = context?.applicationContext ?: context
    var config: PrivilegeUiConfig = PrivilegeUiConfig()
    var serverConnectedListener: Closeable? = null
    var serverDisconnectedWatcher: Closeable? = null
    var startNotificationPairingAfterPermission: Boolean = false
    var notificationPairingStartedByOwner: Boolean = false
    var pendingExternalStartProviderId: String? = null
    @Volatile
    var serverShutdownRequestedByOwner: Boolean = false
    @Volatile
    var runtimeStartJob: Job? = null
    @Volatile
    var runtimeStartSession: PrivilegeUiRuntimeStartSession? = null
    val runtimeStartGeneration = AtomicLong(0L)
    @Volatile
    var tcpAuthorizationRequest: Closeable? = null
    val tcpAuthorizationRequestGeneration = AtomicLong(0L)
    var wirelessPairingJob: Job? = null
    val wirelessPairingGeneration = AtomicLong(0L)

    fun initializeState(config: PrivilegeUiConfig) {
        val context = requireContext()
        val modes = config.effectiveStartupModes()
        val selected = state.value.selectedStartupMode.takeIf { it in modes }
            ?: PrivilegeUiStartupMode.ADB.takeIf { it in modes }
            ?: modes.first()
        updateState { current ->
            current.copy(
                selectedStartupMode = selected,
                startupModes = modes,
                pairingMessage = current.pairingMessage.ifBlank {
                    context.getString(R.string.priv_ui_pairing_default_message)
                },
                notificationPairingRunning = PrivilegeAdbPairingService.running,
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
        appendStartupLog(line)
    }

    fun showSnackbar(message: String) {
        snackbarMessageState.tryEmit(message)
    }

    fun showFailure(message: String) {
        showSnackbar(message)
    }

    fun showFailure(throwable: Throwable) {
        showFailure(throwable.failureMessage())
    }

    fun appendStartupLog(line: PrivilegeStartupLogLine) {
        appendStartupLogLines(
            line.message.toPrivilegeUiStartupLogLines()
                .map { "[${line.source}] $it" },
        )
    }

    fun appendStartupLog(text: String) {
        appendStartupLogLines(text.toPrivilegeUiStartupLogLines())
    }

    private fun appendStartupLogLines(lines: List<String>) {
        if (lines.isEmpty()) return
        updateState { current ->
            current.copy(
                startupLogLines = (current.startupLogLines + lines)
                    .takeLast(MAX_STARTUP_LOG_LINES),
            )
        }
    }

    fun clearStartupLog() {
        updateState { it.copy(startupLogLines = emptyList()) }
    }

    fun requireContext(): Context =
        applicationContext ?: error("PrivilegeUiViewModel requires an application context")

    fun text(id: Int, vararg args: Any): String =
        requireContext().getString(id, *args)

    fun currentAdbDeviceNameOverride(): String? =
        config.adbDeviceName
            ?.toPrivilegeUiAdbDeviceNameText()
            ?.ifBlank { null }

    override fun close() {
        val request = tcpAuthorizationRequest
        val session = runtimeStartSession
        runtimeStartGeneration.incrementAndGet()
        runtimeStartSession = null
        runtimeStartJob?.cancel()
        runtimeStartJob = null
        session?.close()
        tcpAuthorizationRequestGeneration.incrementAndGet()
        tcpAuthorizationRequest = null
        request?.close()
        wirelessPairingGeneration.incrementAndGet()
        wirelessPairingJob?.cancel()
        wirelessPairingJob = null
    }

    private fun PrivilegeUiConfig.effectiveStartupModes(): List<PrivilegeUiStartupMode> {
        val modes = startupModes
            .filterTo(mutableSetOf()) { it in USER_VISIBLE_AUTHORIZATION_MODE_ORDER }
        if (externalStartProviders.isNotEmpty()) modes += PrivilegeUiStartupMode.EXTERNAL
        if (modes.isEmpty()) modes += PrivilegeUiStartupMode.ROOT
        return USER_VISIBLE_AUTHORIZATION_MODE_ORDER.filter { it in modes }
    }

    private companion object {
        private const val MAX_STARTUP_LOG_LINES = 240

        val USER_VISIBLE_AUTHORIZATION_MODE_ORDER = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
            PrivilegeUiStartupMode.EXTERNAL,
        )
    }
}

internal fun String.toPrivilegeUiStartupLogLines(): List<String> =
    lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .toList()
