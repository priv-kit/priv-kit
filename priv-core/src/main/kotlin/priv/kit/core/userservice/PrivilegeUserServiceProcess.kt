package priv.kit.core.userservice

/**
 * Selects where a UserService runs and which identity a dedicated process requests.
 * Changing UID does not change the inherited SELinux domain or recreate an Android app process.
 */
public enum class PrivilegeUserServiceProcess(internal val uid: Int) {
    /** Dedicated process inheriting the server UID. */
    INHERIT(-2),
    /** Default mode: service hosted inside the privileged server process. */
    EMBEDDED(-1),
    /** Dedicated process requesting UID 0. */
    ROOT(0),
    /** Dedicated process requesting UID 1000. */
    SYSTEM(1000),
    /** Dedicated process requesting UID 2000. */
    SHELL(2000);

    public val isEmbedded: Boolean get() = this == EMBEDDED

    internal companion object {
        fun fromUid(uid: Int): PrivilegeUserServiceProcess =
            entries.firstOrNull { it.uid == uid }
                ?: throw IllegalArgumentException("Unsupported UserService UID: $uid")
    }
}
