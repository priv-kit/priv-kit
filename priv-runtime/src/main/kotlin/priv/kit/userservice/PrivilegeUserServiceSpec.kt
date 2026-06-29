package priv.kit.userservice

public data class PrivilegeUserServiceSpec public constructor(
    public val serviceClassName: String,
    public val tag: String = DEFAULT_TAG,
    public val version: Int = 1,
    public val processMode: PrivilegeUserServiceProcessMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
    public val ownerDeathPolicy: PrivilegeUserServiceOwnerDeathPolicy =
        PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH,
    public val destroyTimeoutMillis: Long = DEFAULT_DESTROY_TIMEOUT_MILLIS,
) {
    init {
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag.isNotBlank()) { "tag must not be blank" }
        require(version >= 0) { "version must not be negative" }
    }

    public fun id(): PrivilegeUserServiceId = PrivilegeUserServiceId.from(this)

    internal companion object {
        internal const val DEFAULT_TAG: String = "default"
        internal const val DEFAULT_DESTROY_TIMEOUT_MILLIS: Long = 10_000L
    }
}
