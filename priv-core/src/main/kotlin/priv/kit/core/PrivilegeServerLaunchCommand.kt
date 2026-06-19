package priv.kit.core

data class PrivilegeServerLaunchCommand(
    val token: String,
    val foregroundCommandLine: String,
    val detachedCommandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val packageName: String,
    val mode: PrivilegeMode,
    val protocolVersion: Int,
    val serverVersion: String,
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
)
