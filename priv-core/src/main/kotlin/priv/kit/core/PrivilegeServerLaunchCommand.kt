package priv.kit.core

public data class PrivilegeServerLaunchCommand public constructor(
    public val foregroundCommandLine: String,
    public val detachedCommandLine: String,
    public val classpath: String,
    public val classpathIdentity: String,
    public val mainClass: String,
    public val providerAuthority: String,
    public val packageName: String,
    public val protocolVersion: Int,
    public val userId: Int = 0,
)
