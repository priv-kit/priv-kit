package priv.kit.root

public data class PrivilegeRootCommand public constructor(
    public val commandLine: String,
    public val classpath: String,
    public val mainClass: String,
    public val providerAuthority: String,
)
