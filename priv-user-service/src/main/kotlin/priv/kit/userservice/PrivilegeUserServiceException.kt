package priv.kit.userservice

import android.os.RemoteException

sealed class PrivilegeUserServiceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class PrivilegeUserServiceManagerUnavailableException(
    cause: Throwable? = null,
) : PrivilegeUserServiceException("Privilege UserService manager is unavailable", cause)

class PrivilegeUserServiceDeclarationException(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

class PrivilegeUserServiceStartException(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

class PrivilegeUserServiceBindException(
    message: String,
    cause: Throwable? = null,
) : PrivilegeUserServiceException(message, cause)

class PrivilegeUserServiceNotRunningException(
    message: String,
) : PrivilegeUserServiceException(message)

class PrivilegeUserServiceRemoteCallException(
    message: String,
    cause: RemoteException,
) : PrivilegeUserServiceException(message, cause)
