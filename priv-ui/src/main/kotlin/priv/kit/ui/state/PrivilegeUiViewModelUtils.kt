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

internal fun String.toPrivilegeUiHostAdbShellScriptCommand(): String =
    ADB_SHELL_PREFIX + "sh " + toPrivilegeUiShellArg()

internal fun String.toPrivilegeUiAdbVisibleExternalPath(): String =
    if (startsWith(PRIMARY_EXTERNAL_STORAGE_PREFIX)) {
        SDCARD_PREFIX + removePrefix(PRIMARY_EXTERNAL_STORAGE_PREFIX)
    } else {
        this
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

private fun String.toPrivilegeUiShellArg(): String =
    if (isNotEmpty() && all(::isPrivilegeUiShellBareChar)) {
        this
    } else {
        "'" + replace("'", "'\"'\"'") + "'"
    }

private fun isPrivilegeUiShellBareChar(char: Char): Boolean =
    char in 'A'..'Z' ||
        char in 'a'..'z' ||
        char in '0'..'9' ||
        char == '/' ||
        char == '.' ||
        char == '_' ||
        char == '-' ||
        char == ':' ||
        char == '=' ||
        char == '@' ||
        char == '%' ||
        char == '+' ||
        char == ',' ||
        char == '~'

private const val ADB_SHELL_PREFIX = "adb shell "
private const val PRIMARY_EXTERNAL_STORAGE_PREFIX = "/storage/emulated/0/"
private const val SDCARD_PREFIX = "/sdcard/"
