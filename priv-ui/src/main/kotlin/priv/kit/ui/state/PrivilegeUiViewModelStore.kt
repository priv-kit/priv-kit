package priv.kit.ui.state

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartItemState
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiState
import priv.kit.ui.PrivilegeUiText
import priv.kit.ui.R
import priv.kit.ui.asString
import priv.kit.ui.effectiveStartupModes
import priv.kit.ui.privilegeUiText
import java.util.UUID

internal class PrivilegeUiViewModelStore(
    context: Context? = null,
) : AutoCloseable {
    val state = MutableStateFlow(PrivilegeUiState())
    private val snackbarTextState = MutableSharedFlow<PrivilegeUiText>(extraBufferCapacity = 1)
    val snackbarTexts: SharedFlow<PrivilegeUiText> = snackbarTextState.asSharedFlow()

    @Volatile
    var applicationContext: Context? = context?.applicationContext ?: context
    var config: PrivilegeUiConfig = PrivilegeUiConfig()
    val notificationPairingOwnerId: String = UUID.randomUUID().toString()
    @Volatile
    var serverShutdownRequestedByOwner: Boolean = false

    fun initializeState(config: PrivilegeUiConfig) {
        val modes = config.effectiveStartupModes()
        val selected = state.value.selectedStartupMode.takeIf { it in modes }
            ?: PrivilegeUiStartupMode.ADB.takeIf { it in modes }
            ?: modes.first()
        updateState { current ->
            current.copy(
                selectedStartupMode = selected,
                startupModes = modes,
                pairingText = current.pairingText
                    ?: privilegeUiText(R.string.priv_ui_pairing_default_message),
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

    fun showSnackbar(text: PrivilegeUiText) {
        snackbarTextState.tryEmit(text)
    }

    fun showFailure(failureKind: PrivilegeUiFailureKind) {
        showSnackbar(resourceText(failureKind.messageResId))
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

    fun resourceText(@StringRes id: Int, vararg args: Any): PrivilegeUiText =
        privilegeUiText(id, *args)

    fun resolveText(text: PrivilegeUiText): String =
        text.asString(requireContext())

    fun text(@StringRes id: Int, vararg args: Any): String =
        resolveText(resourceText(id, *args))

    fun currentAdbDeviceNameOverride(): String? =
        config.adbDeviceName
            ?.toPrivilegeUiAdbDeviceNameText()
            ?.ifBlank { null }

    override fun close() = Unit

    private companion object {
        const val MAX_STARTUP_LOG_LINES = 240
    }
}

internal fun String.toPrivilegeUiStartupLogLines(): List<String> =
    lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .toList()
