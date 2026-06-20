package priv.kit.userservice

interface PrivilegeUserServiceHost {
    val uid: Int
    val pid: Int

    fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): PrivilegeUserServiceProcessHandle

    fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess

    fun awaitDedicatedProcessExit(
        handle: PrivilegeUserServiceProcessHandle,
        timeoutMillis: Long,
    ): Boolean

    fun killDedicatedProcess(handle: PrivilegeUserServiceProcessHandle)
}

class PrivilegeUserServiceProcessHandle(
    val process: Process,
)
