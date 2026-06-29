package priv.kit.internal.server

import priv.kit.internal.core.PrivilegeProtocol

internal data class PrivilegeServerConfig(
    val token: String = "",
    val packageName: String,
    val userId: Int = 0,
    val classpath: String = System.getenv("CLASSPATH").orEmpty(),
    val protocolVersion: Int = PrivilegeProtocol.VERSION,
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
)
