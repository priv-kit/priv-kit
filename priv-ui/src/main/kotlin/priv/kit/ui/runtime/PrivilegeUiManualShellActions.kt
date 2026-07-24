package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.shared.PrivilegeBinaryFileStore
import java.io.File
import java.nio.charset.StandardCharsets

internal suspend fun PrivilegeUiViewModelStore.loadManualShellCommand() {
    val directCommandResult = withContext(Dispatchers.IO) {
        runCatching(Privilege::createShellStartCommand)
    }
    val directCommand = directCommandResult.getOrElse { throwable ->
        appendLog(throwable.toPrivilegeUiDiagnosticString())
        updateState { it.copy(manualShellCommandLine = null) }
        return
    }
    val fallbackCommand = directCommand.toPrivilegeUiHostAdbShellCommand()
    val commandLine = withContext(Dispatchers.IO) {
        runCatching {
            createPrivilegeUiManualShellCommand(
                directShellCommand = directCommand,
                externalBootstrapSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                externalFilesDirectory = requireContext().getExternalFilesDir(null),
                scriptWriter = PrivilegeBinaryFileStore::writeAtomically,
            )
        }
    }.getOrElse { throwable ->
        appendLog(throwable.toPrivilegeUiDiagnosticString())
        fallbackCommand
    }
    updateState { it.copy(manualShellCommandLine = commandLine) }
}

internal fun PrivilegeUiViewModelStore.copyManualShellCommand(context: Context) {
    val commandLine = state.value.manualShellCommandLine ?: return
    context.copyToClipboard(
        label = text(R.string.priv_ui_manual_command_clip_label),
        text = commandLine,
    )
}

internal fun createPrivilegeUiManualShellCommand(
    directShellCommand: String,
    externalBootstrapSupported: Boolean,
    externalFilesDirectory: File?,
    scriptWriter: (File, ByteArray) -> Unit,
): String {
    val fallbackCommand = directShellCommand.toPrivilegeUiHostAdbShellCommand()
    if (!externalBootstrapSupported) return fallbackCommand
    val directory = externalFilesDirectory ?: return fallbackCommand
    val scriptFile = File(directory, MANUAL_SHELL_SCRIPT_FILE_NAME)
    synchronized(manualShellScriptLock) {
        scriptWriter(
            scriptFile,
            directShellCommand.toPrivilegeUiNativeStarterScript()
                .toByteArray(StandardCharsets.UTF_8),
        )
    }
    return scriptFile.absolutePath
        .toPrivilegeUiAdbVisibleExternalPath()
        .toPrivilegeUiHostAdbShellScriptCommand()
}

private fun String.toPrivilegeUiNativeStarterScript(): String =
    "#!/system/bin/sh\nexec $this\n"

private const val MANUAL_SHELL_SCRIPT_FILE_NAME = "priv-kit.sh"
private val manualShellScriptLock = Any()
