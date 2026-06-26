package priv.kit.binder

import android.os.RemoteException

public sealed class PrivilegeBinderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class PrivilegeServerDisconnectedException public constructor(
    message: String = "Privilege server Binder is disconnected",
    cause: Throwable? = null,
) : PrivilegeBinderException(message, cause)

public class PrivilegeBinderRemoteCallException public constructor(
    message: String,
    cause: RemoteException,
) : PrivilegeBinderException(message, cause)
