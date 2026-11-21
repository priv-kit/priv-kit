package priv.kit.ui

import android.content.Intent

@ExperimentalPrivilegeUiApi
public enum class PrivilegeUiSettingsDestination { NOTIFICATIONS, APPLICATION_PERMISSIONS, BATTERY_OPTIMIZATION }

/** Tries platform fallbacks only when a destination could not be opened. */
internal suspend fun launchPrivilegeUiSettings(
    intents: List<Intent>,
    launchAndAwait: suspend (Intent) -> Unit,
): Boolean {
    for (intent in intents) {
        try {
            launchAndAwait(intent)
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            // Continue to the next supported settings destination.
        } catch (_: SecurityException) {
            // Continue when a device restricts this destination.
        }
    }
    return false
}

