package priv.kit.core.command

/** Captured output and exit status returned by [PrivilegeCommandProcess.awaitResult]. */
public class PrivilegeCommandResult internal constructor(
    public val exitCode: Int,
    public val stdout: ByteArray,
    public val stderr: ByteArray,
    public val stdoutTruncated: Boolean,
    public val stderrTruncated: Boolean,
)
