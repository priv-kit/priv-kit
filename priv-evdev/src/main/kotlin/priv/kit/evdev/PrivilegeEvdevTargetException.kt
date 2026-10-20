package priv.kit.evdev

/** The display or its touch-device association is unavailable or changed during the operation. */
public class PrivilegeEvdevTargetException(public val displayId: Int, message: String?) : Exception(message)
