package priv.kit.delegate

public data class PrivilegeDelegateStartResult public constructor(
    public val command: PrivilegeDelegateCommand,
    public val executorName: String,
    public val process: PrivilegeDelegateProcess,
)
