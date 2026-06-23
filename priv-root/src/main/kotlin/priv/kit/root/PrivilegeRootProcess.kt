package priv.kit.root

import java.util.Collections

public class PrivilegeRootProcess internal constructor(
    private val process: Process,
    private val output: Output,
) {
    public val isAlive: Boolean
        get() = process.isAlive

    public fun destroy() {
        process.destroy()
    }

    public fun outputText(): String = output.text()

    internal class Output {
        private val lines = Collections.synchronizedList(mutableListOf<String>())

        fun append(source: String, line: String) {
            if (lines.size < MAX_CAPTURED_LINES) {
                lines += "[$source] $line"
            }
        }

        fun text(): String = lines.joinToString("\n").ifBlank { "<no output>" }
    }

    private companion object {
        private const val MAX_CAPTURED_LINES = 40
    }
}
