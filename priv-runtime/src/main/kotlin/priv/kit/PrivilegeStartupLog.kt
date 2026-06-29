package priv.kit

public data class PrivilegeStartupLogLine public constructor(
    public val source: String,
    public val message: String,
    public val timestampMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        require(source.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "source must not contain control line characters"
        }
    }
}

public fun interface PrivilegeStartupLogListener {
    public fun onLog(line: PrivilegeStartupLogLine)
}

internal val PrivilegeStartupLogLine.isPrivKitInternalMetadata: Boolean
    get() =
        message.startsWith("priv-kit-starter-pid=") ||
            message.startsWith("priv-kit-server-log=")
