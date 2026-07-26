package priv.kit.shared

import android.companion.virtual.VirtualDeviceManagerHidden
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.permission.IPermissionManager
import androidx.annotation.ChecksSdkIntAtLeast

private const val REVOKE_WITHOUT_DEVICE_ID: Int = 1

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private const val REVOKE_WITH_DEVICE_ID: Int = 2

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private const val REVOKE_WITH_PERSISTENT_DEVICE_ID: Int = 3

private val revokeRuntimePermissionTypeValue by lazy {
    IPermissionManager::class.java.detectHiddenMethod(
        methodName = "revokeRuntimePermission",
        REVOKE_WITHOUT_DEVICE_ID to listOf(
            String::class,
            String::class,
            Int::class,
            String::class,
        ),
        REVOKE_WITH_DEVICE_ID to listOf(
            String::class,
            String::class,
            Int::class,
            Int::class,
            String::class,
        ),
        REVOKE_WITH_PERSISTENT_DEVICE_ID to listOf(
            String::class,
            String::class,
            String::class,
            Int::class,
            String::class,
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
            Context.DEVICE_ID_DEFAULT,
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
