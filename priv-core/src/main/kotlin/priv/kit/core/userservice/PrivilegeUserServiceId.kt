package priv.kit.core.userservice

internal data class PrivilegeUserServiceId(
    val serviceClassName: String,
    val tag: String = PrivilegeUserServiceSpec.DEFAULT_TAG,
) {
    internal companion object {
        internal fun from(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceId =
            PrivilegeUserServiceId(
                serviceClassName = spec.serviceClassName,
                tag = spec.tag,
            )
    }
}
