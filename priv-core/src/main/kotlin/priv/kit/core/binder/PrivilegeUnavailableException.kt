package priv.kit.core.binder

/**
 * The privileged channel needed by an operation is unavailable. This includes a missing or
 * dead server, a stopped UserService, and a connection that has been unbound.
 * A forwarded target's failure remains a [android.os.DeadObjectException].
 */
public class PrivilegeUnavailableException public constructor(
    message: String = "Privileged channel is unavailable",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun privilegeUnavailable(cause: Throwable?): Nothing =
    throw PrivilegeUnavailableException(cause = cause)
