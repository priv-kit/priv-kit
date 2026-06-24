package priv.kit.runtime

public data class PrivilegeExternalStartCommand public constructor(
    public val commandLine: String,
    public val classpath: String,
    public val mainClass: String,
    public val providerAuthority: String,
)
