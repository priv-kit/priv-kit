package priv.kit.delegate

import priv.kit.core.PrivilegeStartupException

public class PrivilegeDelegateStarter public constructor(
    private val executor: PrivilegeDelegateExecutor,
) {
    @Throws(PrivilegeStartupException::class)
    public fun isAvailable(): Boolean =
        try {
            executor.isAvailable()
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException(
                "Delegate executor ${executor.safeName()} availability check failed",
                throwable,
            )
        }

    @Throws(PrivilegeStartupException::class)
    public fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateStartResult {
        val executorName = executor.safeName()
        if (!isAvailable()) {
            throw PrivilegeStartupException("Delegate executor is not available: $executorName")
        }

        val process = try {
            executor.start(command)
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException(
                "Delegate executor $executorName failed to start Privileged Server",
                throwable,
            )
        }

        return PrivilegeDelegateStartResult(
            command = command,
            executorName = executorName,
            process = process,
        )
    }

    private fun PrivilegeDelegateExecutor.safeName(): String =
        runCatching { name.trim() }
            .getOrNull()
            ?.ifBlank { null }
            ?: javaClass.name
}
