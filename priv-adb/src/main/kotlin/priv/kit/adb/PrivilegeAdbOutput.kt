package priv.kit.adb

import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.isPrivKitInternalMetadata
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
            ).emitIfDisplayable()
        }
    }

    internal fun text(): String =
        synchronized(lines) {
            lines
                .asSequence()
                .filterNot { it.startupLogLine.isPrivKitInternalMetadata }
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

        val startupLogLine: PrivilegeStartupLogLine
            get() = PrivilegeStartupLogLine(
                source = source,
                message = message,
            )
    }

    private fun PrivilegeStartupLogLine.emitIfDisplayable() {
        if (!isPrivKitInternalMetadata) {
            startupLogListener?.onLog(this)
        }
    }
}

private fun String.toPrivilegeAdbOutputLines(): List<String> =
    lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotEmpty() }
        .toList()
        .ifEmpty { listOf(this) }
