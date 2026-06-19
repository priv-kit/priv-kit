package priv.kit.server

import priv.kit.core.PrivilegeMode
import priv.kit.core.PrivilegeProtocol

internal data class PrivilegeServerConfig(
    val token: String,
    val providerAuthority: String,
    val packageName: String,
    val mode: Int = PrivilegeMode.ROOT.value,
    val protocolVersion: Int = PrivilegeProtocol.VERSION,
    val serverVersion: String = PrivilegeProtocol.SERVER_VERSION,
)
