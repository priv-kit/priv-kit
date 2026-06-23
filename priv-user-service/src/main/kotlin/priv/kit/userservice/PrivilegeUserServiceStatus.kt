package priv.kit.userservice

public data class PrivilegeUserServiceStatus public constructor(
    public val id: PrivilegeUserServiceId,
    public val version: Int,
    public val processMode: PrivilegeUserServiceProcessMode,
    public val ownerDeathPolicy: PrivilegeUserServiceOwnerDeathPolicy,
    public val state: PrivilegeUserServiceState,
    public val started: Boolean,
    public val boundCount: Int,
    public val pid: Int,
    public val lastError: String? = null,
)
