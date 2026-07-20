package priv.kit.sample

import android.content.Context
import android.content.pm.UserInfo
import android.os.IBinder
import android.os.IUserManager
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.binder.PrivilegeSystemServiceSource

internal data class PrivilegeSampleUserInfo(
    val id: Int,
    val name: String,
)

private val userManagerGetUsersParameterCount: Int by lazy {
    IUserManager::class.java.declaredMethods
        .first { it.name == "getUsers" }
        .parameterCount
}

internal object PrivilegeSampleUserManager {
    fun createFromCurrentProcess(): PrivilegeSampleUserManagerProxy {
        val serviceBinder = PrivilegeBinderWrapper.fromSystemService(Context.USER_SERVICE)
            ?: throw IllegalStateException("System service not found: ${Context.USER_SERVICE}")
        return create(
            remoteBinder = serviceBinder,
        )
    }

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
        PrivilegeBinderWrapper.fromSystemService(
            serviceName = SERVICE_NAME,
            source = PrivilegeSystemServiceSource.SERVER_PROCESS,
        )
            ?: throw IllegalStateException("Server process system service not found: $SERVICE_NAME")

    fun probeDescriptor(
        remoteBinder: IBinder = createRemoteBinder(),
    ): PrivilegeSampleMqsNativeProbeResult {
        val localResult = runCatching {
            PrivilegeBinderWrapper.fromSystemService(SERVICE_NAME)?.interfaceDescriptor
                ?: throw IllegalStateException("Current process system service not found")
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

private fun IUserManager.getUsersForCurrentPlatform(): List<UserInfo> {
    return when (userManagerGetUsersParameterCount) {
        1 -> getUsers(true)
        3 -> getUsers(true, true, true)
        else -> error(
            "Unsupported IUserManager.getUsers parameter count: $userManagerGetUsersParameterCount",
        )
    }
}
