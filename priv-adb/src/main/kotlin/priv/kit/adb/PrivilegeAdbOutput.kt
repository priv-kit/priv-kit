package priv.kit.adb

import java.util.Collections

class PrivilegeAdbOutput internal constructor() {
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    internal fun append(source: String, text: String) {
        if (lines.size < MAX_CAPTURED_LINES) {
            lines += "[$source] $text"
        }
    }

    fun text(): String = lines.joinToString("\n").ifBlank { "<no output>" }

    companion object {
        private const val MAX_CAPTURED_LINES = 200
    }
}
