package priv.kit.userservice

data class PrivilegeUserServiceStatus(
    val id: PrivilegeUserServiceId,
    val version: Int,
    val processMode: PrivilegeUserServiceProcessMode,
    val ownerDeathPolicy: PrivilegeUserServiceOwnerDeathPolicy,
    val state: PrivilegeUserServiceState,
    val started: Boolean,
    val boundCount: Int,
    val pid: Int,
    val lastError: String? = null,
)
