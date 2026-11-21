package priv.kit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import priv.kit.shared.PrivilegeManifestPermissions

internal fun Context.isBatteryOptimizationRestricted(): Boolean =
    runCatching {
        val powerManager = getSystemService(PowerManager::class.java) ?: return@runCatching false
        !powerManager.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

internal fun Context.privilegeUiBatteryOptimizationIntents(): List<Intent> {
    val context = this
    val packageUri = Uri.fromParts("package", packageName, null)
    return buildList {
        if (
            PrivilegeManifestPermissions.isDeclared(
                context,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            )
        ) {
            add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        }
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
}

@ExperimentalPrivilegeUiApi
public fun isPrivilegeUiBatteryOptimizationRestricted(context: Context): Boolean =
    context.isBatteryOptimizationRestricted()
