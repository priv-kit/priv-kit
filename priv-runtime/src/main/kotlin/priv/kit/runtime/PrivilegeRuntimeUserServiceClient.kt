package priv.kit.runtime

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import priv.kit.userservice.IPrivilegeUserServiceManager
import priv.kit.userservice.PrivilegeUserServiceBindException
import priv.kit.userservice.PrivilegeUserServiceContract
import priv.kit.userservice.PrivilegeUserServiceDeclarationException
import priv.kit.userservice.PrivilegeUserServiceManagerUnavailableException
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import priv.kit.userservice.PrivilegeUserServiceRemoteCallException
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException

internal class PrivilegeRuntimeUserServiceClient(
    private val managerProvider: () -> IBinder?,
) {
    private val ownerBinder = Binder()

    fun start(spec: PrivilegeUserServiceSpec) {
        val response = callManager("start UserService") {
            it.startUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
    }

    fun bind(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection {
        val response = callManager("bind UserService") {
            it.bindUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
        val connectionId = response.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)
            ?: throw PrivilegeUserServiceBindException("UserService bind response is missing a connection id")
        val binder = response.getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER)
            ?: throw PrivilegeUserServiceBindException("UserService bind response is missing a service Binder")
        return PrivilegeUserServiceConnection(
            id = connectionId,
            spec = spec,
            binder = binder,
            unbind = ::unbind,
        )
    }

    fun stop(spec: PrivilegeUserServiceSpec) {
        val response = callManager("stop UserService") {
            it.stopUserService(PrivilegeUserServiceContract.requestBundle(spec))
        }
        ensureSuccess(response)
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
