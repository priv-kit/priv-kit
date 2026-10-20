package priv.kit.core

import androidx.annotation.RestrictTo

/**
 * Internal cross-artifact signal that the native starter could not stop an existing server.
 */
internal class PrivilegeExistingServerStopException(
    message: String,
    cause: Throwable? = null,
) : PrivilegeStartupException(message, cause)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public fun Throwable.isPrivilegeExistingServerStopFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is PrivilegeExistingServerStopException }
