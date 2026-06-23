package priv.kit.core

public data class PrivilegeServerInfo public constructor(
    public val uid: Int,
    public val pid: Int,
    public val launchMode: Int,
    public val protocolVersion: Int,
    public val serverVersion: String,
)
