package priv.kit.delegate

import priv.kit.core.PrivilegeStartupException

public interface PrivilegeDelegateExecutor {
    public val name: String
        get() = javaClass.name

    @Throws(PrivilegeStartupException::class)
    public fun isAvailable(): Boolean = true

    @Throws(PrivilegeStartupException::class)
    public fun start(command: PrivilegeDelegateCommand): PrivilegeDelegateProcess
}
