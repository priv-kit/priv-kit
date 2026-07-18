package priv.kit.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal fun Context.isPrivilegeUiBatteryOptimizationPromptVisible(): Boolean =
    runCatching {
        val powerManager = getSystemService(PowerManager::class.java) ?: return@runCatching false
        !powerManager.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

internal fun Context.requestPrivilegeUiBatteryOptimizationExemption(): Boolean {
    val packageUri = Uri.fromParts("package", packageName, null)
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    return intents.any { intent -> tryStartPrivilegeUiSettingsActivity(intent) }
}

private fun Context.tryStartPrivilegeUiSettingsActivity(intent: Intent): Boolean {
    val launchIntent = Intent(intent).apply {
        if (findPrivilegeUiActivity() == null) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    try {
        startActivity(launchIntent)
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
}

private tailrec fun Context.findPrivilegeUiActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPrivilegeUiActivity()
    else -> null
}
