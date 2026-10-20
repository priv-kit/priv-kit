package priv.kit.input

/** The input service reported a command failure through its exit code or output. */
public class InputShellCommandException(
    public val exitCode: Int,
    public val output: String,
) : Exception("Input command failed ($exitCode): ${output.trim()}")
