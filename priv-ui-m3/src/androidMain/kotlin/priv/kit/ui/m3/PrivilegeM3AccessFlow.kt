package priv.kit.ui.m3

internal suspend fun runPrivilegeM3PermissionFlow(
    request: suspend () -> Boolean,
    requiresSettings: suspend () -> Boolean,
    granted: () -> Boolean,
    explain: suspend () -> Boolean,
    openSettings: suspend () -> Unit,
): Boolean {
    val result = request()
    if (result || !requiresSettings()) return result
    if (!explain()) throw kotlinx.coroutines.CancellationException("Permission request cancelled")
    openSettings()
    return granted()
}

internal suspend fun runPrivilegeM3BatteryOptimizationFlow(
    restricted: () -> Boolean,
    explain: suspend () -> Boolean,
    request: suspend () -> Boolean,
): Boolean {
    if (!restricted()) return true
    if (!explain()) return false
    return request()
}
