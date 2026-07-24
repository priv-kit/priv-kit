package priv.kit.core.binder

/**
 * Thrown when the Privileged Server Binder is missing or has died.
 */
public class PrivilegeServerUnavailableException public constructor(
    message: String = "Privilege server is unavailable",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun serverUnavailable(cause: Throwable?): Nothing =
    throw PrivilegeServerUnavailableException(cause = cause)
