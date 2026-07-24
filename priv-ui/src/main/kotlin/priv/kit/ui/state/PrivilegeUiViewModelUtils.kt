package priv.kit.ui.state

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import priv.kit.shared.toPrivilegeAdbDeviceNameText
import priv.kit.shared.toPrivilegeDiagnosticString

internal fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun String.toPrivilegeUiAdbDeviceNameText(): String =
    toPrivilegeAdbDeviceNameText()

internal fun String.toPrivilegeUiHostAdbShellCommand(): String {
    val command = trim()
    return if (command.startsWith(ADB_SHELL_PREFIX)) {
        command
    } else {
        ADB_SHELL_PREFIX + command
    }
}

internal fun privilegeUiStaticTcpOpenCommand(tcpPort: Int): String =
    "adb tcpip $tcpPort"

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
internal fun isPrivilegeUiWirelessAdbSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

internal fun Throwable.failureMessage(): String =
    message ?: javaClass.simpleName

internal fun Throwable.toPrivilegeUiDiagnosticString(): String =
    toPrivilegeDiagnosticString()

private const val ADB_SHELL_PREFIX = "adb shell "
