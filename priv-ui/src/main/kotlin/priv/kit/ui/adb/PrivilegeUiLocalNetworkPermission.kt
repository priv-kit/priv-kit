package priv.kit.ui.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal fun privilegeUiRequiredLocalNetworkPermission(context: Context): String? {
    val permission = privilegeUiLocalNetworkPermissionName() ?: return null
    return permission.takeIf {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}

private fun privilegeUiLocalNetworkPermissionName(): String? =
    if (privilegeUiRequiresLocalNetworkPermissionForSdk(Build.VERSION.SDK_INT)) {
        Manifest.permission.ACCESS_LOCAL_NETWORK
    } else {
        null
    }

internal fun privilegeUiRequiresLocalNetworkPermissionForSdk(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.CINNAMON_BUN
