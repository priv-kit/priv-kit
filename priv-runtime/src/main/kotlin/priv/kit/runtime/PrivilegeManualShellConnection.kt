package priv.kit.runtime

import priv.kit.core.PrivilegePendingHandshake
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeStartupException

class PrivilegeManualShellConnection internal constructor(
    val command: PrivilegeManualShellCommand,
    private val pendingHandshake: PrivilegePendingHandshake,
    private val onHandshake: (PrivilegeServerHandshakeResult) -> PrivilegeSession,
) {
    @Throws(PrivilegeStartupException::class)
    fun awaitSession(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): PrivilegeSession {
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
