package priv.kit.ui

internal fun PrivilegeUiConfig.effectiveStartupModes(): List<PrivilegeUiStartupMode> {
    val externalEnabled = externalStartProviders.isNotEmpty()
    val modes = startupModes.filterTo(mutableListOf()) {
        it != PrivilegeUiStartupMode.EXTERNAL || externalEnabled
    }
    if (externalEnabled && PrivilegeUiStartupMode.EXTERNAL !in modes) {
        modes += PrivilegeUiStartupMode.EXTERNAL
    }
    return modes
}
