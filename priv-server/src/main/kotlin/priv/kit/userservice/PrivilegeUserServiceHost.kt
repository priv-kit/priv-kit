package priv.kit.userservice

internal interface PrivilegeUserServiceHost {
    val uid: Int
    val pid: Int
    val packageName: String
    val userId: Int

    fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): Process

    fun awaitDedicatedProcess(
        token: String,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess

    fun awaitDedicatedProcessExit(
        process: Process,
        timeoutMillis: Long,
    ): Boolean

    fun killDedicatedProcess(process: Process)
}
