package priv.kit.delegate

public interface PrivilegeDelegateProcess {
    public val isAlive: Boolean

    public fun destroy(): Unit

    public fun outputText(): String

    public companion object {
        public fun unmanaged(outputText: String = NO_OUTPUT): PrivilegeDelegateProcess =
            StaticPrivilegeDelegateProcess(
                isAlive = true,
                outputText = outputText,
            )

        public fun completed(outputText: String = NO_OUTPUT): PrivilegeDelegateProcess =
            StaticPrivilegeDelegateProcess(
                isAlive = false,
                outputText = outputText,
            )

        private const val NO_OUTPUT = "<no output>"
    }
}

private class StaticPrivilegeDelegateProcess(
    override val isAlive: Boolean,
    private val outputText: String,
) : PrivilegeDelegateProcess {
    override fun destroy() = Unit

    override fun outputText(): String =
        outputText.ifBlank { "<no output>" }
}
