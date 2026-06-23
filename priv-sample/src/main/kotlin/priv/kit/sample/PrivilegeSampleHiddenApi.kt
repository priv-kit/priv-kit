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
    fun createFromCurrentProcess(): PrivilegeSampleUserManagerProxy {
        val serviceBinder = createCurrentProcessBinder()
        return create(
            remoteBinder = PrivilegeRuntime.createRemoteBinderWrapper(serviceBinder),
        )
    }

    private fun createCurrentProcessBinder(): IBinder =
        ServiceManager.getService(Context.USER_SERVICE)
            ?: throw IllegalStateException("System service not found: ${Context.USER_SERVICE}")

    private fun create(remoteBinder: IBinder): PrivilegeSampleUserManagerProxy =
        PrivilegeSampleUserManagerProxy(IUserManager.Stub.asInterface(remoteBinder))
}

internal data class PrivilegeSampleMqsNativeProbeResult(
    val localDescriptor: String?,
    val localError: String?,
    val remoteDescriptor: String?,
    val remoteError: String?,
)

internal object PrivilegeSampleMqsNative {
    private const val SERVICE_NAME = "miui.mqsas.IMQSNative"

    fun createRemoteBinder(): IBinder =
        PrivilegeRuntime.createRemoteSystemServiceBinder(SERVICE_NAME)

    fun probeDescriptor(
        remoteBinder: IBinder = createRemoteBinder(),
    ): PrivilegeSampleMqsNativeProbeResult {
        val localResult = runCatching {
            ServiceManager.getService(SERVICE_NAME)?.interfaceDescriptor
                ?: throw IllegalStateException("Current process ServiceManager returned null")
        }
        val remoteResult = runCatching {
            remoteBinder.interfaceDescriptor
                ?: throw IllegalStateException("Remote Binder descriptor is unavailable")
        }
        return PrivilegeSampleMqsNativeProbeResult(
            localDescriptor = localResult.getOrNull(),
            localError = localResult.exceptionOrNull()?.toSummaryString(),
            remoteDescriptor = remoteResult.getOrNull(),
            remoteError = remoteResult.exceptionOrNull()?.toSummaryString(),
        )
    }

    private fun Throwable.toSummaryString(): String =
        "${javaClass.simpleName}: ${message ?: javaClass.name}"
}

internal class PrivilegeSampleUserManagerProxy(
    private val userManager: IUserManager,
) {
    fun getUsers(): List<PrivilegeSampleUserInfo> =
        userManager.getUsersForCurrentPlatform().map { user ->
            PrivilegeSampleUserInfo(
                id = user.id,
                name = user.name?.trim().orEmpty(),
            )
        }
}

private fun IUserManager.getUsersForCurrentPlatform(): List<UserInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getUsers(true, true, true)
    } else {
        try {
            getUsers(true)
        } catch (_: NoSuchMethodError) {
            getUsers(true, true, true)
        }
    }
