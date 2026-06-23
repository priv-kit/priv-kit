package priv.kit.delegate

public data class PrivilegeDelegateCommand public constructor(
    public val foregroundCommandLine: String,
    public val detachedCommandLine: String,
    public val classpath: String,
    public val mainClass: String,
    public val providerAuthority: String,
)
