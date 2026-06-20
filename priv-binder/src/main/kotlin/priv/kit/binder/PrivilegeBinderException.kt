package priv.kit.binder

import android.os.RemoteException

sealed class PrivilegeBinderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class PrivilegeServerDisconnectedException(
    message: String = "Privilege server Binder is disconnected",
    cause: Throwable? = null,
) : PrivilegeBinderException(message, cause)

class PrivilegeBinderRemoteCallException(
    message: String,
    cause: RemoteException,
) : PrivilegeBinderException(message, cause)

class PrivilegeBinderEndpointDeadException(
    cause: Throwable? = null,
) : PrivilegeBinderException("Binder endpoint is dead", cause)

class PrivilegeBinderEndpointNotFoundException : PrivilegeBinderException("Binder endpoint was not found")
