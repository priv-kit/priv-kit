package priv.kit.core

import androidx.annotation.RestrictTo

/**
 * Internal cross-artifact signal that the native starter could not stop an existing server.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeExistingServerStopException public constructor(
    message: String,
    cause: Throwable? = null,
) : PrivilegeStartupException(message, cause)
