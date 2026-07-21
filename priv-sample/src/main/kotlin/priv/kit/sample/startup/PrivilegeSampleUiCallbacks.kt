package priv.kit.sample.startup

import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.PrivilegeUiConfig

internal data class PrivilegeSamplePrivilegeUiCallbacks(
    val config: PrivilegeUiConfig,
    val back: () -> Unit,
    val connected: (PrivilegeServerInfo) -> Unit,
)

internal const val SHIZUKU_PERMISSION_REQUEST_CODE = 42
