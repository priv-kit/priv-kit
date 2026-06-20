package priv.kit.userservice

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException

class PrivilegeUserServiceClient(
    private val managerProvider: () -> IBinder?,
) {
    private val ownerBinder = Binder()

    fun start(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus {
        val response = callManager("start UserService") {
            it.startUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
        return PrivilegeUserServiceContract.statusFrom(response)
    }

    fun bind(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection {
        val response = callManager("bind UserService") {
            it.bindUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
        val connectionId = requireNotNull(response.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)) {
            "UserService bind response is missing a connection id"
        }
        val binder = requireNotNull(response.getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER)) {
            "UserService bind response is missing a service Binder"
        }
        return PrivilegeUserServiceConnection(
            id = connectionId,
            spec = spec,
            binder = binder,
            status = PrivilegeUserServiceContract.statusFrom(response),
            unbind = ::unbind,
        )
    }

    fun stop(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus {
        val response = callManager("stop UserService") {
            it.stopUserService(PrivilegeUserServiceContract.requestBundle(spec))
        }
        ensureSuccess(response)
        return PrivilegeUserServiceContract.statusFrom(response)
    }

    fun getStatus(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus {
        val response = callManager("get UserService status") {
            it.getUserServiceStatus(PrivilegeUserServiceContract.requestBundle(spec))
        }
        ensureSuccess(response)
        return PrivilegeUserServiceContract.statusFrom(response)
    }

    private fun unbind(connectionId: String) {
        val response = callManager("unbind UserService") {
            it.unbindUserService(connectionId)
        }
        ensureSuccess(response)
    }

    private inline fun callManager(
        operation: String,
        block: (IPrivilegeUserServiceManager) -> Bundle,
    ): Bundle {
        val managerBinder = try {
            managerProvider()
        } catch (throwable: Throwable) {
            throw PrivilegeUserServiceManagerUnavailableException(throwable)
        } ?: throw PrivilegeUserServiceManagerUnavailableException()
        if (!managerBinder.pingBinder()) {
            throw PrivilegeUserServiceManagerUnavailableException()
        }
        val manager = IPrivilegeUserServiceManager.Stub.asInterface(managerBinder)
            ?: throw PrivilegeUserServiceManagerUnavailableException()

        return try {
            block(manager)
        } catch (exception: DeadObjectException) {
            throw PrivilegeUserServiceManagerUnavailableException(exception)
        } catch (exception: RemoteException) {
            throw PrivilegeUserServiceRemoteCallException("Failed to $operation", exception)
        }
    }

    private fun ensureSuccess(response: Bundle) {
        if (response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false)) {
            return
        }
        val type = response.getString(PrivilegeUserServiceContract.KEY_ERROR_TYPE)
            ?: PrivilegeUserServiceContract.ERROR_UNAVAILABLE
        val message = response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
            ?: "UserService operation failed"
        throw when (type) {
            PrivilegeUserServiceContract.ERROR_DECLARATION ->
                PrivilegeUserServiceDeclarationException(message)
            PrivilegeUserServiceContract.ERROR_START ->
                PrivilegeUserServiceStartException(message)
            PrivilegeUserServiceContract.ERROR_BIND ->
                PrivilegeUserServiceBindException(message)
            PrivilegeUserServiceContract.ERROR_NOT_RUNNING ->
                PrivilegeUserServiceNotRunningException(message)
            else -> PrivilegeUserServiceManagerUnavailableException()
        }
    }
}
