package priv.kit.userservice

data class PrivilegeUserServiceId(
    val serviceClassName: String,
    val tag: String = PrivilegeUserServiceSpec.DEFAULT_TAG,
) {
    init {
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag.isNotBlank()) { "tag must not be blank" }
    }

    companion object {
        fun from(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceId =
            PrivilegeUserServiceId(
                serviceClassName = spec.serviceClassName,
                tag = spec.tag,
            )
    }
}
