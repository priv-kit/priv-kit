package priv.kit.core.command

/** Describes a non-interactive process to start in the connected privileged server. */
public class PrivilegeCommand public constructor(
    arguments: List<String>,
    environment: Map<String, String> = emptyMap(),
    public val workingDirectory: String? = null,
) {
    public val arguments: List<String> = arguments.toList()
    public val environment: Map<String, String> = environment.toMap()

    init {
        require(this.arguments.isNotEmpty()) { "arguments must not be empty" }
        require(this.arguments.first().isNotBlank()) { "executable must not be blank" }
        require(this.arguments.none { '\u0000' in it }) {
            "arguments must not contain NUL characters"
        }
        require(this.environment.keys.none { it.isEmpty() || '=' in it || '\u0000' in it }) {
            "environment variable names must be non-empty and must not contain '=' or NUL"
        }
        require(this.environment.values.none { '\u0000' in it }) {
            "environment variable values must not contain NUL characters"
        }
        require(workingDirectory == null || workingDirectory.startsWith('/')) {
            "workingDirectory must be absolute"
        }
    }

    override fun toString(): String =
        "PrivilegeCommand(arguments=$arguments, environmentKeys=${environment.keys}, " +
            "workingDirectory=$workingDirectory)"
}
