package priv.kit.userservice

import android.os.Binder
import android.os.Bundle
import android.os.IBinder

public class PrivilegeUserServiceManagerBinder public constructor(
    private val registry: PrivilegeUserServiceRegistry,
) : IPrivilegeUserServiceManager.Stub() {
    override fun startUserService(
        request: Bundle,
        client: IBinder?,
    ): Bundle =
        call {
            PrivilegeUserServiceContract.successBundle(
                registry.start(
                    spec = PrivilegeUserServiceContract.specFrom(request),
                    client = client ?: Binder(),
                ),
            )
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
                status = result.status,
            )
        }

    override fun unbindUserService(connectionId: String): Bundle =
        call {
            PrivilegeUserServiceContract.successBundle(registry.unbind(connectionId))
        }

    override fun stopUserService(request: Bundle): Bundle =
        call {
            PrivilegeUserServiceContract.successBundle(
                registry.stop(PrivilegeUserServiceContract.specFrom(request)),
            )
        }

    override fun getUserServiceStatus(request: Bundle): Bundle =
        call {
            PrivilegeUserServiceContract.successBundle(
                registry.getStatus(PrivilegeUserServiceContract.specFrom(request)),
            )
        }

    public fun destroyOnOwnerDeath() {
        registry.destroyOnOwnerDeath()
    }

    public fun destroyAll() {
        registry.destroyAll()
    }

    private inline fun call(block: () -> Bundle): Bundle =
        try {
            block()
        } catch (exception: PrivilegeUserServiceDeclarationException) {
            PrivilegeUserServiceContract.errorBundle(
                type = PrivilegeUserServiceContract.ERROR_DECLARATION,
                message = exception.message ?: "Invalid UserService declaration",
            )
        } catch (exception: PrivilegeUserServiceStartException) {
            PrivilegeUserServiceContract.errorBundle(
                type = PrivilegeUserServiceContract.ERROR_START,
                message = exception.message ?: "UserService start failed",
            )
        } catch (exception: PrivilegeUserServiceBindException) {
            PrivilegeUserServiceContract.errorBundle(
                type = PrivilegeUserServiceContract.ERROR_BIND,
                message = exception.message ?: "UserService bind failed",
            )
        } catch (exception: PrivilegeUserServiceNotRunningException) {
            PrivilegeUserServiceContract.errorBundle(
                type = PrivilegeUserServiceContract.ERROR_NOT_RUNNING,
                message = exception.message ?: "UserService is not running",
            )
        } catch (throwable: Throwable) {
            PrivilegeUserServiceContract.errorBundle(
                type = PrivilegeUserServiceContract.ERROR_UNAVAILABLE,
                message = throwable.message ?: throwable.javaClass.name,
            )
        }
}
