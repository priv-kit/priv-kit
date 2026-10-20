package priv.kit.core.adb

import java.io.IOException

internal class PrivilegeAdbLocalNetworkAccessException(
    endpoint: PrivilegeAdbEndpoint,
    cause: Throwable,
) : Exception("ADB local network endpoint $endpoint is unavailable", cause)

public fun Throwable.isPrivilegeAdbLocalNetworkAccessFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is PrivilegeAdbLocalNetworkAccessException }

internal fun privilegeAdbError(message: Any): Nothing =
    throw IOException(message.toString())

internal fun Throwable.toLocalNetworkAccessFailure(endpoint: PrivilegeAdbEndpoint): Throwable =
    if (!endpoint.isLocalHost && !isAdbKeyNotAuthorized()) {
        PrivilegeAdbLocalNetworkAccessException(endpoint, this)
    } else {
        this
    }
