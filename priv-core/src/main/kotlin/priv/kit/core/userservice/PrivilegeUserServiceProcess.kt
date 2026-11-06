package priv.kit.core.userservice

/**
 * Selects where a UserService runs. Null inherits the server UID in a dedicated process;
 * -1 embeds the service in the server. Explicit UIDs currently support root, system and shell.
 * Changing UID does not change the inherited SELinux domain or recreate an Android app process.
 */
public data class PrivilegeUserServiceProcess(public val uid: Int? = null) {
    init {
        require(uid == null || uid in setOf(-1, 0, 1000, 2000)) {
            "UserService UID must be null, -1 (embedded), 0, 1000 or 2000"
        }
    }

    public val isEmbedded: Boolean get() = uid == -1

    public companion object {
        public val EMBEDDED: PrivilegeUserServiceProcess = PrivilegeUserServiceProcess(-1)
        public val DEDICATED: PrivilegeUserServiceProcess = PrivilegeUserServiceProcess()
    }
}
