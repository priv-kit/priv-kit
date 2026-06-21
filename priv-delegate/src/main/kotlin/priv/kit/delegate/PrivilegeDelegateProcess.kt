package priv.kit.delegate

interface PrivilegeDelegateProcess {
    val isAlive: Boolean

    fun destroy()

    fun outputText(): String

    companion object {
        fun unmanaged(outputText: String = NO_OUTPUT): PrivilegeDelegateProcess =
            StaticPrivilegeDelegateProcess(
                isAlive = true,
                outputText = outputText,
            )

        fun completed(outputText: String = NO_OUTPUT): PrivilegeDelegateProcess =
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
