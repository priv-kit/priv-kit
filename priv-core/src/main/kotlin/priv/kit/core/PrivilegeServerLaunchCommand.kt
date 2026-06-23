package priv.kit.core

public data class PrivilegeServerLaunchCommand public constructor(
    public val token: String,
    public val foregroundCommandLine: String,
    public val detachedCommandLine: String,
    public val classpath: String,
    public val classpathIdentity: String,
    public val mainClass: String,
    public val providerAuthority: String,
    public val packageName: String,
    public val launchMode: PrivilegeLaunchMode,
    public val protocolVersion: Int,
    public val serverVersion: String,
    public val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    public val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    public val userId: Int = 0,
)
