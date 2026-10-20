package priv.kit.core.binder

/**
 * Thrown when the Privileged Server Binder is missing or has died.
 * Used by both Core operations and Binder wrappers before forwarding a transaction.
 * A raw transaction's endpoint failure remains a [android.os.DeadObjectException].
 */
public class PrivilegeServerUnavailableException public constructor(
    message: String = "Privilege server is unavailable",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun serverUnavailable(cause: Throwable?): Nothing =
    throw PrivilegeServerUnavailableException(cause = cause)
