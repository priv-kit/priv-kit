package priv.kit.binder

import android.os.RemoteException

public sealed class PrivilegeBinderException protected constructor(
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

public class PrivilegeBinderEndpointDeadException public constructor(
    cause: Throwable? = null,
) : PrivilegeBinderException("Binder endpoint is dead", cause)

public class PrivilegeBinderEndpointNotFoundException public constructor() :
    PrivilegeBinderException("Binder endpoint was not found")
