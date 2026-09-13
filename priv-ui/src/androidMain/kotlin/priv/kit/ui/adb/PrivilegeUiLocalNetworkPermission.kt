package priv.kit.ui.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat

internal fun privilegeUiRequiredLocalNetworkPermission(context: Context): String? {
    if (!isPrivilegeUiLocalNetworkPermissionSupported()) return null
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    return permission.takeIf {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.CINNAMON_BUN)
private fun isPrivilegeUiLocalNetworkPermissionSupported(): Boolean =
    privilegeUiRequiresLocalNetworkPermissionForSdk(Build.VERSION.SDK_INT)

internal fun privilegeUiRequiresLocalNetworkPermissionForSdk(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.CINNAMON_BUN
