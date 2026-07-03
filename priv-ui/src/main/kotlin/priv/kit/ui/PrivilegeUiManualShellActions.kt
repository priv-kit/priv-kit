package priv.kit.ui

import android.content.Context
import priv.kit.Privilege

internal class PrivilegeUiManualShellActions(
    private val store: PrivilegeUiViewModelStore,
) {
    fun loadCommand() {
        val commandLine = runCatching {
            Privilege.createShellStartCommand()
                .toPrivilegeUiHostAdbStaticTcpCommand(store.config.tcpPort)
        }.getOrElse { throwable ->
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            null
        }
        store.updateState { it.copy(manualShellCommandLine = commandLine) }
    }

    fun copyCommand(context: Context) {
        val commandLine = store.state.value.manualShellCommandLine ?: return
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_manual_command_clip_label),
            text = commandLine,
        )
    }
}
