package priv.kit.runtime

import priv.kit.core.PrivilegePendingHandshake
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException

class PrivilegeManualShellConnection internal constructor(
    val command: PrivilegeManualShellCommand,
    private val pendingHandshake: PrivilegePendingHandshake,
    private val onHandshake: (PrivilegeServerHandshakeResult) -> PrivilegeServerInfo,
) {
    @Throws(PrivilegeStartupException::class)
    fun awaitServer(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): PrivilegeServerInfo {
        val handshakeResult = pendingHandshake.await(timeoutMillis)
        return onHandshake(handshakeResult)
    }

    fun cancel() {
        PrivilegeServerHandshakeRegistry.cancel(command.token)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
