package priv.kit.userservice

public interface PrivilegeUserServiceHost {
    public val uid: Int
    public val pid: Int
    public val packageName: String
    public val userId: Int

    public fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): PrivilegeUserServiceProcessHandle

    public fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess

    public fun awaitDedicatedProcessExit(
        handle: PrivilegeUserServiceProcessHandle,
        timeoutMillis: Long,
    ): Boolean

    public fun killDedicatedProcess(handle: PrivilegeUserServiceProcessHandle)
}

public class PrivilegeUserServiceProcessHandle public constructor(
    public val process: Process,
)
