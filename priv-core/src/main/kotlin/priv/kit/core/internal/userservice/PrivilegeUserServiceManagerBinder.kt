package priv.kit.core.internal.userservice

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import priv.kit.core.userservice.PrivilegeUserServiceException

internal class PrivilegeUserServiceManagerBinder internal constructor(
    private val registry: PrivilegeUserServiceRegistry,
) : IPrivilegeUserServiceManager.Stub() {
    override fun startUserService(
        request: Bundle,
        client: IBinder?,
    ): Bundle =
        call {
            registry.start(
                spec = PrivilegeUserServiceContract.specFrom(request),
                client = client ?: Binder(),
            )
            PrivilegeUserServiceContract.successBundle()
        }

    override fun bindUserService(
        request: Bundle,
        client: IBinder?,
    ): Bundle =
        call {
            val result = registry.bind(
                spec = PrivilegeUserServiceContract.specFrom(request),
                client = client ?: Binder(),
            )
            PrivilegeUserServiceContract.bindSuccessBundle(
                connectionId = result.connectionId,
                binder = result.binder,
            )
        }

    override fun unbindUserService(connectionId: String): Bundle =
        call {
            registry.unbind(connectionId)
            PrivilegeUserServiceContract.successBundle()
        }

    override fun stopUserService(request: Bundle): Bundle =
        call {
            registry.stop(PrivilegeUserServiceContract.specFrom(request))
            PrivilegeUserServiceContract.successBundle()
        }

    fun destroyOnOwnerDeath() {
        registry.destroyOnOwnerDeath()
    }

    fun destroyAll() {
        registry.destroyAll()
    }

    private inline fun call(block: () -> Bundle): Bundle =
        try {
            block()
        } catch (exception: PrivilegeUserServiceException) {
            PrivilegeUserServiceContract.errorBundle(
                message = exception.message ?: "UserService operation failed",
            )
        }
}
