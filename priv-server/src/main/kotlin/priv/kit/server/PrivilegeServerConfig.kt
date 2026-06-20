package priv.kit.server

import priv.kit.core.PrivilegeLaunchMode
import priv.kit.core.PrivilegeProtocol

internal data class PrivilegeServerConfig(
    val token: String,
    val providerAuthority: String,
    val packageName: String,
    val userId: Int = 0,
    val classpath: String = System.getenv("CLASSPATH").orEmpty(),
    val classpathIdentity: String = "",
    val launchMode: Int = PrivilegeLaunchMode.ROOT.value,
    val protocolVersion: Int = PrivilegeProtocol.VERSION,
    val serverVersion: String = PrivilegeProtocol.SERVER_VERSION,
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
)
