package priv.kit.adb

open class PrivilegeAdbException : Exception {
    constructor(message: String, cause: Throwable? = null) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

class PrivilegeAdbInvalidPairingCodeException : PrivilegeAdbException("Invalid ADB pairing code")

class PrivilegeAdbKeyException(cause: Throwable) : PrivilegeAdbException("Failed to load ADB key", cause)

internal fun privilegeAdbError(message: Any): Nothing =
    throw PrivilegeAdbException(message.toString())
