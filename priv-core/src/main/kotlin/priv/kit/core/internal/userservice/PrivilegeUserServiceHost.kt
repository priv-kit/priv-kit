package priv.kit.core.internal.userservice

import priv.kit.core.userservice.PrivilegeUserServiceSpec

internal interface PrivilegeUserServiceHost {
    val packageName: String
    val userId: Int

    fun startDedicatedProcess(
        spec: PrivilegeUserServiceSpec,
        token: String,
    ): Process

    fun awaitDedicatedProcess(
        token: String,
        handle: Process,
        timeoutMillis: Long,
    ): IPrivilegeUserServiceProcess

    fun killDedicatedProcess(process: Process)
}
