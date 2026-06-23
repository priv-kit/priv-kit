package priv.kit.runtime

import priv.kit.core.PrivilegePendingHandshake
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException

public class PrivilegeManualShellConnection internal constructor(
    public val command: PrivilegeManualShellCommand,
    private val pendingHandshake: PrivilegePendingHandshake,
    private val onHandshake: (PrivilegeServerHandshakeResult) -> PrivilegeServerInfo,
) {
    @Throws(PrivilegeStartupException::class)
    public fun awaitServer(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): PrivilegeServerInfo {
        val handshakeResult = pendingHandshake.await(timeoutMillis)
        return onHandshake(handshakeResult)
    }

    public fun cancel() {
        PrivilegeServerHandshakeRegistry.cancel(command.token)
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 10 * 60 * 1000L
    }
}
