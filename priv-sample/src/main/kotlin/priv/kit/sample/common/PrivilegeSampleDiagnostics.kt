package priv.kit.sample.common

internal fun Throwable.toDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(8).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}
