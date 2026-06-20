package priv.kit.userservice

data class PrivilegeUserServiceSpec(
    val serviceClassName: String,
    val tag: String = DEFAULT_TAG,
    val version: Int = 1,
    val processMode: PrivilegeUserServiceProcessMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
    val ownerDeathPolicy: PrivilegeUserServiceOwnerDeathPolicy =
        PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH,
    val destroyTimeoutMillis: Long = DEFAULT_DESTROY_TIMEOUT_MILLIS,
) {
    init {
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag.isNotBlank()) { "tag must not be blank" }
        require(version >= 0) { "version must not be negative" }
    }

    fun id(): PrivilegeUserServiceId = PrivilegeUserServiceId.from(this)

    companion object {
        const val DEFAULT_TAG = "default"
        const val DEFAULT_DESTROY_TIMEOUT_MILLIS = 10_000L
    }
}
