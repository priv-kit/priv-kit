package priv.kit.userservice

import android.os.RemoteException

public sealed class PrivilegeUserServiceException protected constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class PrivilegeUserServiceManagerUnavailableException public constructor(
    cause: Throwable? = null,
) : PrivilegeUserServiceException("Privilege UserService manager is unavailable", cause)

public class PrivilegeUserServiceDeclarationException public constructor(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

public class PrivilegeUserServiceStartException public constructor(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

public class PrivilegeUserServiceBindException public constructor(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

public class PrivilegeUserServiceNotRunningException : PrivilegeUserServiceException {
    public constructor(message: String) : super(message)

    public constructor(
        message: String,
        cause: Throwable?,
    ) : super(message, cause)
}

public class PrivilegeUserServiceRemoteCallException public constructor(
    message: String,
    cause: RemoteException,
) : PrivilegeUserServiceException(message, cause)
