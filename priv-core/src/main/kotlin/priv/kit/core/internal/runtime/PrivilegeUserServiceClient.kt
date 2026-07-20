package priv.kit.core.internal.runtime

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.binder.serverControlCall
import priv.kit.core.internal.userservice.IPrivilegeUserServiceManager
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.userservice.PrivilegeUserServiceException
import priv.kit.core.userservice.PrivilegeUserServiceSpec

internal class PrivilegeUserServiceClient(
    private val managerProvider: () -> IBinder,
) {
    private val ownerBinder = Binder()

    fun start(spec: PrivilegeUserServiceSpec) {
        val response = callManager {
            it.startUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
    }

    fun bind(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection {
        val response = callManager {
            it.bindUserService(PrivilegeUserServiceContract.requestBundle(spec), ownerBinder)
        }
        ensureSuccess(response)
        val connectionId = response.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)
            ?: throw PrivilegeUserServiceException("UserService bind response is missing a connection id")
        val binder = response.getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER)
            ?: throw PrivilegeUserServiceException("UserService bind response is missing a service Binder")
        return PrivilegeUserServiceConnection(
            id = connectionId,
            binder = binder,
            unbind = ::unbind,
        )
    }

    fun stop(spec: PrivilegeUserServiceSpec) {
        val response = callManager {
            it.stopUserService(PrivilegeUserServiceContract.requestBundle(spec))
        }
        ensureSuccess(response)
    }

    private fun unbind(connectionId: String) {
        val response = callManager {
            it.unbindUserService(connectionId)
        }
        ensureSuccess(response)
    }

    private inline fun callManager(
        block: (IPrivilegeUserServiceManager) -> Bundle,
    ): Bundle {
        val manager = IPrivilegeUserServiceManager.Stub.asInterface(managerProvider())
        return serverControlCall {
            block(manager)
        }
    }

    private fun ensureSuccess(response: Bundle) {
        if (response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false)) {
            return
        }
        val message = response.getString(PrivilegeUserServiceContract.KEY_ERROR_MESSAGE)
            ?: "UserService operation failed"
        throw PrivilegeUserServiceException(message)
    }
}
