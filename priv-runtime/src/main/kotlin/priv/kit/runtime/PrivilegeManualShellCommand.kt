package priv.kit.runtime

public data class PrivilegeManualShellCommand public constructor(
    public val token: String,
    public val commandLine: String,
    public val classpath: String,
    public val mainClass: String,
    public val providerAuthority: String,
)
