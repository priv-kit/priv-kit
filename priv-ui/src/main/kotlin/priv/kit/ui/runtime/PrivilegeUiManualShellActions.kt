package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege

internal suspend fun PrivilegeUiViewModelStore.loadManualShellCommand() {
    val commandLine = withContext(Dispatchers.IO) {
        runCatching {
            Privilege.createShellStartCommand()
                .toPrivilegeUiHostAdbShellCommand()
        }
    }.getOrElse { throwable ->
        appendLog(throwable.toPrivilegeUiDiagnosticString())
        null
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
