package priv.kit.adb

public open class PrivilegeAdbException : Exception {
    public constructor(message: String, cause: Throwable? = null) : super(message, cause)
    public constructor(cause: Throwable) : super(cause)
}

public class PrivilegeAdbKeyException public constructor(cause: Throwable) :
    PrivilegeAdbException("Failed to load ADB key", cause)

internal fun privilegeAdbError(message: Any): Nothing =
    throw PrivilegeAdbException(message.toString())
