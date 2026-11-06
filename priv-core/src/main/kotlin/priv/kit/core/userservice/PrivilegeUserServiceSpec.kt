package priv.kit.core.userservice

public data class PrivilegeUserServiceSpec public constructor(
    /** Null uses the built-in Binder forwarding service, with no application AIDL required. */
    public val serviceClassName: String? = null,
    public val tag: String = DEFAULT_TAG,
    public val version: Int = 1,
    public val process: PrivilegeUserServiceProcess = PrivilegeUserServiceProcess.DEDICATED,
    /**
     * Controls if the service should run in daemon mode.
     *
     * Under non-daemon mode, a bind-only service is destroyed when the last connection
     * closes, and a started service is destroyed when the owner app process dies.
     * Under daemon mode, the service is kept until it is stopped or the privileged server exits.
     */
    public val daemon: Boolean = false,
) {
    init {
        require(serviceClassName == null || serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag.isNotBlank()) { "tag must not be blank" }
        require(version >= 0) { "version must not be negative" }
    }

    internal fun id(): PrivilegeUserServiceId = PrivilegeUserServiceId.from(this)

    internal companion object {
        internal const val DEFAULT_TAG: String = "default"
    }
}
