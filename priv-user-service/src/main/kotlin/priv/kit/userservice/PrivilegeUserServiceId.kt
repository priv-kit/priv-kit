package priv.kit.userservice

public data class PrivilegeUserServiceId public constructor(
    public val serviceClassName: String,
    public val tag: String = PrivilegeUserServiceSpec.DEFAULT_TAG,
) {
    init {
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(tag.isNotBlank()) { "tag must not be blank" }
    }

    public companion object {
        public fun from(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceId =
            PrivilegeUserServiceId(
                serviceClassName = spec.serviceClassName,
                tag = spec.tag,
            )
    }
}
