package priv.kit.adb

import priv.kit.PrivilegeStartupLogLine
import priv.kit.PrivilegeStartupLogListener
import java.util.Collections

internal class PrivilegeAdbOutput internal constructor(
    private val startupLogListener: PrivilegeStartupLogListener? = null,
) {
    private val lines = Collections.synchronizedList(mutableListOf<OutputLine>())

    internal fun append(source: String, text: String) {
        text.toPrivilegeAdbOutputLines().forEach { line ->
            if (lines.size < MAX_CAPTURED_LINES) {
                lines += OutputLine(source, line)
            }
            PrivilegeStartupLogLine(
                source = source,
                message = line,
            ).emit()
        }
    }

    internal fun text(): String =
        synchronized(lines) {
            lines
                .joinToString("\n") { it.formatted }
                .ifBlank { "<no output>" }
        }

    private companion object {
        private const val MAX_CAPTURED_LINES = 200
    }

    private data class OutputLine(
        val source: String,
        val message: String,
    ) {
        val formatted: String
            get() = "[$source] $message"
    }

    private fun PrivilegeStartupLogLine.emit() {
        startupLogListener?.onLog(this)
    }
}

private fun String.toPrivilegeAdbOutputLines(): List<String> =
    lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotEmpty() }
        .toList()
        .ifEmpty { listOf(this) }
