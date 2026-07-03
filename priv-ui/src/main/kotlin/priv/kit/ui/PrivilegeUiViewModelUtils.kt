package priv.kit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

internal fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun String.toPrivilegeUiAdbDeviceNameText(): String =
    replace('\u0000', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(MAX_ADB_DEVICE_NAME_LENGTH)

internal fun String.toPrivilegeUiHostAdbShellCommand(): String {
    val command = trim()
    return if (command.startsWith(ADB_SHELL_PREFIX)) {
        command
    } else {
        ADB_SHELL_PREFIX + command
    }
}

internal fun isPrivilegeUiWirelessAdbSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

internal fun Throwable.failureMessage(): String =
    message ?: javaClass.simpleName

internal fun Throwable.toPrivilegeUiDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_DIAGNOSTIC_DEPTH) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(MAX_DIAGNOSTIC_STACK_LINES).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

private const val MAX_ADB_DEVICE_NAME_LENGTH = 128
private const val ADB_SHELL_PREFIX = "adb shell "
private const val MAX_DIAGNOSTIC_DEPTH = 8
private const val MAX_DIAGNOSTIC_STACK_LINES = 8
