package priv.kit.ui

internal fun PrivilegeUiConfig.effectiveStartupModes(): List<PrivilegeUiStartupMode> {
    val modes = startupModes
        .filterTo(mutableSetOf()) { it in privilegeUiAuthorizationModeOrder }
    if (externalStartProviders.isNotEmpty()) modes += PrivilegeUiStartupMode.EXTERNAL
    if (modes.isEmpty()) modes += PrivilegeUiStartupMode.ROOT
    return privilegeUiAuthorizationModeOrder.filter { it in modes }
}

private val privilegeUiAuthorizationModeOrder = listOf(
    PrivilegeUiStartupMode.ROOT,
    PrivilegeUiStartupMode.ADB,
    PrivilegeUiStartupMode.MANUAL_SHELL,
    PrivilegeUiStartupMode.EXTERNAL,
)
