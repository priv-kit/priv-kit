package priv.kit.shared

/** Formats a bounded throwable cause chain for Priv Kit diagnostics. */
public fun Throwable.toPrivilegeDiagnosticString(
    maxCauseDepth: Int = 8,
    maxStackFramesPerCause: Int = 8,
): String {
    require(maxCauseDepth > 0) { "maxCauseDepth must be positive" }
    require(maxStackFramesPerCause > 0) { "maxStackFramesPerCause must be positive" }

    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < maxCauseDepth) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(maxStackFramesPerCause).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}
