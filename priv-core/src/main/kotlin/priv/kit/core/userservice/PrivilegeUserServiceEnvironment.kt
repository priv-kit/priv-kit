package priv.kit.core.userservice

/**
 * Describes the process environment of a running Privilege UserService.
 *
 * The values are stable for the lifetime of the process and should only be read from code
 * running inside a Privilege UserService.
 */
public object PrivilegeUserServiceEnvironment {
    /**
     * Whether the current UserService is embedded in the Privileged Server process.
     *
     * Returns `false` for a dedicated UserService process. The result is cached after the first
     * read because a process cannot change between embedded and dedicated UserService roles.
     */
    public val isEmbedded: Boolean by lazy {
        System.getProperty(SERVER_PROCESS_PROPERTY) != null
    }

    internal fun markServerProcess() {
        System.setProperty(SERVER_PROCESS_PROPERTY, "")
    }

    private const val SERVER_PROCESS_PROPERTY: String = "priv.kit.server_process"
}
