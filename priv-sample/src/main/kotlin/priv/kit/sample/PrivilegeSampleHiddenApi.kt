package priv.kit.sample

import android.content.Context
import android.content.pm.UserInfo
import android.os.Build
import android.os.IBinder
import android.os.IUserManager
import android.os.ServiceManager
import priv.kit.runtime.PrivilegeRuntime

internal data class PrivilegeSampleUserInfo(
    val id: Int,
    val name: String,
)

internal object PrivilegeSampleUserManager {
    fun create(): PrivilegeSampleUserManagerProxy {
        val serviceBinder = PrivilegeSampleSystemServiceHelper.getSystemService(Context.USER_SERVICE)
            ?: throw IllegalStateException("System service not found: ${Context.USER_SERVICE}")
        val remoteBinder = PrivilegeRuntime.createRemoteBinderWrapper(serviceBinder)
        return PrivilegeSampleUserManagerProxy(IUserManager.Stub.asInterface(remoteBinder))
    }
}

internal class PrivilegeSampleUserManagerProxy(
    private val userManager: IUserManager,
) {
    fun getUsers(): List<PrivilegeSampleUserInfo> =
        userManager.getUsersCompat().map { user ->
            PrivilegeSampleUserInfo(
                id = user.id,
                name = user.name?.trim().orEmpty(),
            )
        }
}

private object PrivilegeSampleSystemServiceHelper {
    fun getSystemService(name: String): IBinder? =
        ServiceManager.getService(name)
}

private fun IUserManager.getUsersCompat(): List<UserInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getUsers(true, true, true)
    } else {
        try {
            getUsers(true)
        } catch (_: NoSuchMethodError) {
            getUsers(true, true, true)
        }
    }
