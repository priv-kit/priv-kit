package priv.kit.delegate

import priv.kit.core.PrivilegeStartupException

interface PrivilegeDelegateExecutor {
    val name: String
        get() = javaClass.name

    @Throws(PrivilegeStartupException::class)
    fun isAvailable(): Boolean = true

    @Throws(PrivilegeStartupException::class)
    fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess
}
