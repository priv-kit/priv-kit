package priv.kit.internal.userservice

import priv.kit.userservice.PrivilegeUserServiceSpec

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

    fun killDedicatedProcess(process: Process)
}
