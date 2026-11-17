package priv.kit.ui.state

import android.content.Context
import android.os.Process
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
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
import priv.kit.ui.PrivilegeUiStartMethod
import priv.kit.ui.runtime.PrivilegeUiStartMethodStore
import java.util.UUID

internal class PrivilegeUiViewModelStore(
    context: Context? = null,
    val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) {
    val state = MutableStateFlow(
        initialPrivilegeUiState(
            context = context,
            config = config,
            selectedStartupMode = persistedStartupMode(context, config),
        ),
    )
    private val snackbarTextState = MutableSharedFlow<PrivilegeUiText>(extraBufferCapacity = 1)
    val snackbarTexts: SharedFlow<PrivilegeUiText> = snackbarTextState.asSharedFlow()

    @Volatile
    var applicationContext: Context? = context?.applicationContext ?: context
    val notificationPairingOwnerId: String = UUID.randomUUID().toString()
    @Volatile
    var serverShutdownRequestedByOwner: Boolean = false

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

    fun setExternalStartAuthorization(
        id: String,
        authorized: Boolean?,
    ) {
        updateState { current ->
            current.copy(
                externalStartItems = current.externalStartItems.map { item ->
                    if (item.id == id) {
                        item.copy(
                            authorized = authorized,
                            statusLoaded = true,
                        )
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun refreshExternalStartLabels(context: Context) {
        val labels = config.externalStartProviders.associate { it.id to it.getLabel(context) }
        updateState { current ->
            current.copy(externalStartItems = current.externalStartItems.map { item ->
                item.copy(label = labels.getValue(item.id))
            })
        }
    }

    fun externalStartLabel(id: String): CharSequence =
        state.value.externalStartItems.first { it.id == id }.label

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

    private companion object {
        const val MAX_STARTUP_LOG_LINES = 240
    }
}

private fun initialPrivilegeUiState(
    context: Context?,
    config: PrivilegeUiConfig,
    selectedStartupMode: PrivilegeUiStartupMode?,
): PrivilegeUiState {
    val startupModes = config.effectiveStartupModes()
    val scriptPath = config.manualShellScriptPath
    val labelContext = context?.let(ContextCompat::getContextForLanguage)
    return PrivilegeUiState(
        selectedStartupMode = selectedStartupMode?.takeIf { it in startupModes }
            ?: PrivilegeUiStartupMode.ADB.takeIf { it in startupModes }
            ?: startupModes.first(),
        startupModes = startupModes,
        manualShellCommandLine = if (context != null && scriptPath != null && Process.myUserHandle().hashCode() == 0) {
            privilegeUiManualShellScriptCommand(context.packageName, scriptPath)
        } else null,
        pairingText = privilegeUiText(R.string.priv_ui_pairing_default_message),
        externalStartItems = config.externalStartProviders.map { provider ->
            PrivilegeUiExternalStartItemState(
                id = provider.id,
                label = labelContext?.let(provider::getLabel) ?: provider.id,
            )
        },
    )
}

private fun persistedStartupMode(
    context: Context?,
    config: PrivilegeUiConfig,
): PrivilegeUiStartupMode? {
    val applicationContext = context?.applicationContext ?: context ?: return null
    return runCatching {
        when (val method = PrivilegeUiStartMethodStore(applicationContext).read()) {
            PrivilegeUiStartMethod.Root -> PrivilegeUiStartupMode.ROOT
            PrivilegeUiStartMethod.AdbWireless,
            PrivilegeUiStartMethod.AdbTcpip,
            -> PrivilegeUiStartupMode.ADB
            is PrivilegeUiStartMethod.External -> PrivilegeUiStartupMode.EXTERNAL.takeIf {
                config.externalStartProviders.any { provider -> provider.id == method.providerId }
            }
            null -> null
        }
    }.getOrNull()
}

internal fun String.toPrivilegeUiStartupLogLines(): List<String> =
    lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .toList()
