package priv.kit.ui.state

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import priv.kit.PrivilegeStartupLogLine
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartItemState
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiState
import priv.kit.ui.R
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartSession
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class PrivilegeUiViewModelStore(
    context: Context? = null,
) : AutoCloseable {
    val state = MutableStateFlow(PrivilegeUiState())
    private val snackbarMessageState = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = snackbarMessageState.asSharedFlow()

    @Volatile
    var applicationContext: Context? = context?.applicationContext ?: context
    var config: PrivilegeUiConfig = PrivilegeUiConfig()
    var serverConnectedListener: Closeable? = null
    var serverDisconnectedWatcher: Closeable? = null
    var startNotificationPairingAfterPermission: Boolean = false
    val notificationPairingOwnerId: String = UUID.randomUUID().toString()
    var pendingExternalStartProviderId: String? = null
    @Volatile
    var serverShutdownRequestedByOwner: Boolean = false
    @Volatile
    var runtimeStartJob: Job? = null
    @Volatile
    var runtimeStartSession: PrivilegeUiRuntimeStartSession? = null
    val runtimeStartGeneration = AtomicLong(0L)
    @Volatile
    var tcpAuthorizationRequest: AutoCloseable? = null
    val tcpAuthorizationRequestGeneration = AtomicLong(0L)

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
                notificationPairingRunning = false,
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

    fun updateStateAndAppendStartupLog(
        line: String?,
        transform: (PrivilegeUiState) -> PrivilegeUiState,
    ) {
        val lines = line?.toPrivilegeUiStartupLogLines().orEmpty()
        state.update { current ->
            val updated = transform(current)
            if (lines.isEmpty()) {
                updated
            } else {
                updated.copy(
                    startupLogLines = (updated.startupLogLines + lines)
                        .takeLast(MAX_STARTUP_LOG_LINES),
                )
            }
        }
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
        var request: AutoCloseable? = null
        synchronized(this) {
            request = tcpAuthorizationRequest
            tcpAuthorizationRequestGeneration.incrementAndGet()
            tcpAuthorizationRequest = null
        }
        runCatching { request?.close() }
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
