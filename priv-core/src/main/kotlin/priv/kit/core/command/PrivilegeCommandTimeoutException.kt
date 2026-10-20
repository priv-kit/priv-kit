package priv.kit.core.command

import java.io.IOException

/** Thrown after a command exceeds its configured execution timeout. */
public class PrivilegeCommandTimeoutException public constructor(
    public val timeoutMillis: Long,
) : IOException("Command timed out after $timeoutMillis ms")
