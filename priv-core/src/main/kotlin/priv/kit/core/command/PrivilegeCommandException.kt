package priv.kit.core.command

/** Thrown when a privileged command cannot be started or completed. */
public open class PrivilegeCommandException public constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Thrown after a command exceeds its configured execution timeout. */
public class PrivilegeCommandTimeoutException public constructor(
    public val timeoutMillis: Long,
) : PrivilegeCommandException("Command timed out after $timeoutMillis ms")
