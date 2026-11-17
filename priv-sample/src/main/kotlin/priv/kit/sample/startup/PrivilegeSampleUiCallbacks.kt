package priv.kit.sample.startup

import priv.kit.ui.PrivilegeUiConfig

internal data class PrivilegeSamplePrivilegeUiCallbacks(
    val config: PrivilegeUiConfig,
    val back: () -> Unit,
)
