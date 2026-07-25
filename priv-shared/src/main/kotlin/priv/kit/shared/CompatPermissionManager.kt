package priv.kit.shared

import android.annotation.SuppressLint
import android.companion.virtual.VirtualDeviceManagerHidden
import android.content.ContextHidden
import android.os.IBinder
import android.permission.IPermissionManager

private const val REVOKE_WITHOUT_DEVICE_ID: Int = 1
private const val REVOKE_WITH_DEVICE_ID: Int = 2
private const val REVOKE_WITH_PERSISTENT_DEVICE_ID: Int = 3

private val revokeRuntimePermissionTypeValue by lazy {
    IPermissionManager::class.java.detectHiddenMethod(
        methodName = "revokeRuntimePermission",
        REVOKE_WITHOUT_DEVICE_ID to listOf(
            String::class.java,
            String::class.java,
            Int::class.java,
            String::class.java,
        ),
        REVOKE_WITH_DEVICE_ID to listOf(
            String::class.java,
            String::class.java,
            Int::class.java,
            Int::class.java,
            String::class.java,
        ),
        REVOKE_WITH_PERSISTENT_DEVICE_ID to listOf(
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.java,
            String::class.java,
        ),
    )
}

public class CompatPermissionManager(
    binder: IBinder,
) {
    private val permissionManager = IPermissionManager.Stub.asInterface(binder)

    public fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): Unit = when (revokeRuntimePermissionTypeValue) {
        REVOKE_WITHOUT_DEVICE_ID -> permissionManager.revokeRuntimePermission(
            packageName,
            permissionName,
            userId,
            null,
        )

        REVOKE_WITH_DEVICE_ID -> permissionManager.revokeRuntimePermission(
            packageName,
            permissionName,
            @SuppressLint("NewApi") ContextHidden.DEVICE_ID_DEFAULT,
            userId,
            null,
        )

        REVOKE_WITH_PERSISTENT_DEVICE_ID -> permissionManager.revokeRuntimePermission(
            packageName,
            permissionName,
            VirtualDeviceManagerHidden.PERSISTENT_DEVICE_ID_DEFAULT,
            userId,
            null,
        )

        else -> throw NoSuchMethodException()
    }
}
