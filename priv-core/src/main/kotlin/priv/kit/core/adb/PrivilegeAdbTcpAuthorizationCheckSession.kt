package priv.kit.core.adb

import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST
import java.io.Closeable

public class PrivilegeAdbTcpAuthorizationCheckSession internal constructor(
    private val identity: PrivilegeAdbIdentity,
    private val publicKeyFingerprint: String,
    private val tcpPort: Int,
    private val clientFactory: () -> PrivilegeAdbAuthorizationConnection,
) : Closeable {
    private val lock = Any()

    @Volatile
    private var closed = false
    private var client: PrivilegeAdbAuthorizationConnection? = null

    public suspend fun check(): PrivilegeAdbAuthorizationCheckResult =
        cancellableAdbCall(cancel = ::close, block = ::checkBlocking)

    private fun checkBlocking(): PrivilegeAdbAuthorizationCheckResult {
        val output = PrivilegeAdbOutput()
        output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
        output.append("diag", "ADB public key fingerprint=$publicKeyFingerprint")
        output.append("diag", "ADB TCP authorization check port=$tcpPort, persistent=true")
        if (closed) {
            return closedResult(output)
        }

        checkExistingConnection(output)?.let { return it }
        if (closed) {
            return closedResult(output)
        }

        return connectNewClient(output)
    }

    override fun close() {
        closed = true
        val clientToClose = synchronized(lock) {
            val currentClient = client
            client = null
            currentClient
        }
        clientToClose?.close()
    }

    private fun checkExistingConnection(output: PrivilegeAdbOutput): PrivilegeAdbAuthorizationCheckResult? {
        val activeClient = synchronized(lock) {
            client ?: return null
        }
        // Keep the static TCP transport alive for the same Android 13 behavior that
        // affects wireless ADB: repeated connect-and-disconnect probes can leave the
        // Settings UI saying ADB is enabled while the daemon no longer accepts starts.
        output.append(
            "diag",
            "Reusing ADB TCP authorization check connection on $PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST:$tcpPort",
        )
        return try {
            activeClient.keepAlive(output)
            output.append("diag", "ADB TCP authorization check connection is still alive")
            successResult(output)
        } catch (throwable: Throwable) {
            closeClient(activeClient)
            throwable.rethrowIfInterrupted()
            val failureMessage = throwable.toFailureMessage()
            output.append(
                "diag",
                "ADB TCP authorization check connection failed on $PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST:$tcpPort: $failureMessage",
            )
            null
        }
    }

    private fun connectNewClient(output: PrivilegeAdbOutput): PrivilegeAdbAuthorizationCheckResult {
        val newClient = clientFactory()
        if (!setClient(newClient)) {
            newClient.close()
            return closedResult(output)
        }
        return try {
            output.append(
                "diag",
                "Opening persistent ADB TCP authorization check connection on $PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST:$tcpPort",
            )
            val status = newClient.checkAuthorization(output)
            if (status == PrivilegeAdbAuthorizationStatus.AUTHORIZED) {
                output.append(
                    "diag",
                    "ADB TCP authorization check succeeded on $PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST:$tcpPort",
                )
                successResult(output)
            } else {
                closeClient(newClient)
                statusResult(
                    status = status,
                    output = output,
                )
            }
        } catch (throwable: Throwable) {
            closeClient(newClient)
            throwable.rethrowIfInterrupted()
            val failureMessage = throwable.toFailureMessage()
            output.append(
                "diag",
                "ADB TCP authorization check failed on $PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST:$tcpPort: $failureMessage",
            )
            throwable.toTcpAuthorizationCheckResult(
                output = output,
                identity = identity,
                publicKeyFingerprint = publicKeyFingerprint,
            )
        }
    }

    private fun setClient(newClient: PrivilegeAdbAuthorizationConnection): Boolean =
        synchronized(lock) {
            if (closed) {
                false
            } else {
                client = newClient
                true
            }
        }

    private fun closeClient(expected: PrivilegeAdbAuthorizationConnection) {
        val clientToClose = synchronized(lock) {
            if (client === expected) {
                client = null
                expected
            } else {
                null
            }
        }
        clientToClose?.close()
    }

    private fun successResult(output: PrivilegeAdbOutput): PrivilegeAdbAuthorizationCheckResult =
        statusResult(
            status = PrivilegeAdbAuthorizationStatus.AUTHORIZED,
            output = output,
        )

    private fun closedResult(output: PrivilegeAdbOutput): PrivilegeAdbAuthorizationCheckResult =
        PrivilegeAdbAuthorizationCheckResult(
            status = PrivilegeAdbAuthorizationStatus.ERROR,
            outputText = output.text(),
            identity = identity,
            publicKeyFingerprint = publicKeyFingerprint,
            failureMessage = "ADB TCP authorization check session is closed",
        )

    private fun statusResult(
        status: PrivilegeAdbAuthorizationStatus,
        output: PrivilegeAdbOutput,
    ): PrivilegeAdbAuthorizationCheckResult =
        PrivilegeAdbAuthorizationCheckResult(
            status = status,
            outputText = output.text(),
            identity = identity,
            publicKeyFingerprint = publicKeyFingerprint,
        )
}
