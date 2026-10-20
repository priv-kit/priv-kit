package priv.kit.evdev

import java.io.IOException

/** A device open/query error retaining Linux errno. Remote causes are reconstructed. */
public class PrivilegeEvdevSyscallException internal constructor(
    public val errno: Int,
    message: String?,
    cause: Throwable,
) : IOException(message, cause)
