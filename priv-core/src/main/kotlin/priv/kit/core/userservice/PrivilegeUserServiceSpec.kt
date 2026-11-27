package priv.kit.core.userservice

public data class PrivilegeUserServiceSpec public constructor(
    /** Null uses the built-in Binder forwarding service, with no application AIDL required. */
    public val serviceClassName: String? = null,
    /** Optional label for separate instances of the same service class. */
    public val tag: String? = null,
    /** Runs inside the privileged server by default. Use INHERIT for a separate process with the same UID. */
    public val process: PrivilegeUserServiceProcess = PrivilegeUserServiceProcess.EMBEDDED,
) {
    init {
        require(serviceClassName == null || serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag == null || tag.isNotBlank()) { "tag must not be blank" }
    }

    internal fun id(): PrivilegeUserServiceId = PrivilegeUserServiceId.from(this)
}
