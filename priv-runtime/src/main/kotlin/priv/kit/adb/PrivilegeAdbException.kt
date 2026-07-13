package priv.kit.adb

internal class PrivilegeAdbException : Exception {
    constructor(message: String, cause: Throwable? = null) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

internal class PrivilegeAdbLocalNetworkAccessException(
    endpoint: PrivilegeAdbEndpoint,
    cause: Throwable,
) : Exception("ADB local network endpoint $endpoint is unavailable", cause)

public fun Throwable.isPrivilegeAdbLocalNetworkAccessFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is PrivilegeAdbLocalNetworkAccessException }

internal fun privilegeAdbError(message: Any): Nothing =
    throw PrivilegeAdbException(message.toString())
