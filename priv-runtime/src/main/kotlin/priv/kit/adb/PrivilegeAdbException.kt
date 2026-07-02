package priv.kit.adb

internal class PrivilegeAdbException : Exception {
    constructor(message: String, cause: Throwable? = null) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

internal fun privilegeAdbError(message: Any): Nothing =
    throw PrivilegeAdbException(message.toString())
