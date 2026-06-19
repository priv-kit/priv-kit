package priv.kit.root

import java.util.Collections

class PrivilegeRootProcess internal constructor(
    private val process: Process,
    private val output: Output,
) {
    val isAlive: Boolean
        get() = process.isAlive

    fun destroy() {
        process.destroy()
    }

    fun outputText(): String = output.text()

    internal class Output {
        private val lines = Collections.synchronizedList(mutableListOf<String>())

        fun append(source: String, line: String) {
            if (lines.size < MAX_CAPTURED_LINES) {
                lines += "[$source] $line"
            }
        }

        fun text(): String = lines.joinToString("\n").ifBlank { "<no output>" }
    }

    companion object {
        private const val MAX_CAPTURED_LINES = 40
    }
}
