package priv.kit.binder

public class PrivilegeServerUnavailableException public constructor(
    message: String = "Privilege server is unavailable",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun serverUnavailable(cause: Throwable? = null): Nothing =
    throw PrivilegeServerUnavailableException(cause = cause)

internal inline fun <T> serverControlCall(block: () -> T): T =
    try {
        block()
    } catch (exception: android.os.RemoteException) {
        serverUnavailable(exception)
    }
