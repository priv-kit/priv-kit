package priv.kit.shared

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Manifest queries shared by Priv Kit's Android implementation modules. */
public object PrivilegeManifestPermissions {
    /** Returns whether [context]'s merged host manifest declares [permission], or false on failure. */
    public fun isDeclared(context: Context, permission: String): Boolean =
        runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                )
            }
            packageInfo.requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)
}
