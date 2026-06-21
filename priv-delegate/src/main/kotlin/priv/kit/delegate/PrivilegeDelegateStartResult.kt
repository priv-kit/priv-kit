package priv.kit.delegate

data class PrivilegeDelegateStartResult(
    val command: PrivilegeDelegateCommand,
    val executorName: String,
    val process: PrivilegeDelegateProcess,
)
