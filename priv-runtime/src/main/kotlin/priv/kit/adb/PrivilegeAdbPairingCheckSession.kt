package priv.kit.adb

import java.io.Closeable

public class PrivilegeAdbPairingCheckSession internal constructor(
    private val identity: PrivilegeAdbIdentity,
    private val publicKeyFingerprint: String,
    private val explicitPort: Int?,
    private val discoverPort: Boolean,
    private val portDiscoveryTimeoutMillis: Long,
    private val discoverConnectPort: (Long) -> Int,
    private val clientFactory: (Int) -> PrivilegeAdbAuthorizationConnection,
) : Closeable {
    private val lock = Any()

    @Volatile
    private var closed = false
    private var client: PrivilegeAdbAuthorizationConnection? = null
    private var connectedPort: Int? = null

    public val port: Int?
        get() = synchronized(lock) {
            connectedPort ?: explicitPort
        }

    public fun check(): PrivilegeAdbPairingCheckResult {
        val output = PrivilegeAdbOutput()
        output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
        output.append("diag", "ADB public key fingerprint=$publicKeyFingerprint")
        output.append(
            "diag",
            "Pairing check explicit=$explicitPort, discover=$discoverPort, persistent=true",
        )
        if (closed) {
            return failureResult(
                port = port,
                output = output,
                failureMessage = "ADB pairing check session is closed",
                status = PrivilegeAdbPairingCheckStatus.ERROR,
            )
        }

        checkExistingConnection(output)?.let { return it }
        if (closed) {
            return failureResult(
                port = port,
                output = output,
                failureMessage = "ADB pairing check session is closed",
                status = PrivilegeAdbPairingCheckStatus.ERROR,
            )
        }

        val portResolution = resolvePort(output)
        return portResolution.port?.let { activePort ->
            connectNewClient(activePort, output)
        } ?: failureResult(
            port = null,
            output = output,
            failureMessage = portResolution.failureMessage,
            status = PrivilegeAdbPairingCheckStatus.UNAVAILABLE,
        )
    }

    override fun close() {
        closed = true
        val clientToClose = synchronized(lock) {
            val currentClient = client
            client = null
            connectedPort = null
            currentClient
        }
        clientToClose?.close()
    }

    private fun checkExistingConnection(output: PrivilegeAdbOutput): PrivilegeAdbPairingCheckResult? {
        val (activeClient, activePort) = synchronized(lock) {
            val currentClient = client ?: return null
            val currentPort = connectedPort ?: return null
            currentClient to currentPort
        }
        // Keep the transport alive between polling ticks. Some Android 13 builds stop
        // accepting wireless ADB after a connect-and-disconnect probe while Settings
        // still shows Wireless Debugging as enabled.
        output.append(
            "diag",
            "Reusing ADB pairing check connection on $PRIVILEGE_ADB_LOCAL_HOST:$activePort",
        )
        return try {
            activeClient.keepAlive(output)
            output.append("diag", "ADB pairing check connection is still alive")
            successResult(
                port = activePort,
                output = output,
            )
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            output.append(
                "diag",
                "ADB pairing check connection failed on $PRIVILEGE_ADB_LOCAL_HOST:$activePort: $failureMessage",
            )
            closeClient(activeClient)
            null
        }
    }

    private fun resolvePort(output: PrivilegeAdbOutput): PortResolution {
        explicitPort?.let { return PortResolution(port = it) }
        if (!discoverPort) {
            output.append("diag", "ADB pairing check skipped because no connect port is available")
            return PortResolution(
                port = null,
                failureMessage = "ADB connect port is not available",
            )
        }
        return runCatching {
            discoverConnectPort(portDiscoveryTimeoutMillis)
        }.fold(
            onSuccess = { port -> PortResolution(port = port) },
            onFailure = { throwable ->
                val failureMessage = throwable.toFailureMessage()
                output.append("diag", "ADB pairing check failed before connect: $failureMessage")
                PortResolution(
                    port = null,
                    failureMessage = failureMessage,
                )
            },
        )
    }

    private fun connectNewClient(
        activePort: Int,
        output: PrivilegeAdbOutput,
    ): PrivilegeAdbPairingCheckResult {
        val newClient = clientFactory(activePort)
        if (!setClient(newClient, activePort)) {
            newClient.close()
            return failureResult(
                port = activePort,
                output = output,
                failureMessage = "ADB pairing check session is closed",
                status = PrivilegeAdbPairingCheckStatus.ERROR,
            )
        }
        return try {
            output.append(
                "diag",
                "Opening persistent ADB pairing check connection on $PRIVILEGE_ADB_LOCAL_HOST:$activePort",
            )
            when (val status = newClient.checkAuthorization(output)) {
                PrivilegeAdbAuthorizationStatus.AUTHORIZED -> {
                    output.append("diag", "ADB pairing check succeeded on $PRIVILEGE_ADB_LOCAL_HOST:$activePort")
                    successResult(
                        port = activePort,
                        output = output,
                    )
                }
                PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> {
                    output.append("diag", "ADB pairing check found unauthorized key on $PRIVILEGE_ADB_LOCAL_HOST:$activePort")
                    closeClient(newClient)
                    failureResult(
                        port = activePort,
                        output = output,
                        failureMessage = "ADB key is not authorized",
                        status = PrivilegeAdbPairingCheckStatus.UNPAIRED,
                    )
                }
                PrivilegeAdbAuthorizationStatus.UNAVAILABLE,
                PrivilegeAdbAuthorizationStatus.ERROR,
                -> {
                    output.append("diag", "ADB pairing check returned $status on $PRIVILEGE_ADB_LOCAL_HOST:$activePort")
                    closeClient(newClient)
                    failureResult(
                        port = activePort,
                        output = output,
                        failureMessage = "ADB pairing check returned $status",
                        status = status.toPairingCheckFailureStatus(),
                    )
                }
            }
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            val status = throwable.toPairingCheckFailureStatus()
            output.append("diag", "ADB pairing check failed on $PRIVILEGE_ADB_LOCAL_HOST:$activePort: $failureMessage")
            closeClient(newClient)
            failureResult(
                port = activePort,
                output = output,
                failureMessage = if (status == PrivilegeAdbPairingCheckStatus.UNPAIRED) {
                    "ADB key is not authorized"
                } else {
                    failureMessage
                },
                status = status,
            )
        }
    }

    private fun setClient(
        newClient: PrivilegeAdbAuthorizationConnection,
        activePort: Int,
    ): Boolean =
        synchronized(lock) {
            if (closed) {
                false
            } else {
                client = newClient
                connectedPort = activePort
                true
            }
        }

    private fun closeClient(expected: PrivilegeAdbAuthorizationConnection) {
        val clientToClose = synchronized(lock) {
            if (client === expected) {
                client = null
                connectedPort = null
                expected
            } else {
                null
            }
        }
        clientToClose?.close()
    }

    private fun successResult(
        port: Int?,
        output: PrivilegeAdbOutput,
    ): PrivilegeAdbPairingCheckResult =
        PrivilegeAdbPairingCheckResult(
            port = port,
            paired = true,
            outputText = output.text(),
            identity = identity,
            publicKeyFingerprint = publicKeyFingerprint,
            status = PrivilegeAdbPairingCheckStatus.PAIRED,
        )

    private fun failureResult(
        port: Int?,
        output: PrivilegeAdbOutput,
        failureMessage: String,
        status: PrivilegeAdbPairingCheckStatus,
    ): PrivilegeAdbPairingCheckResult =
        PrivilegeAdbPairingCheckResult(
            port = port,
            paired = false,
            outputText = output.text(),
            identity = identity,
            publicKeyFingerprint = publicKeyFingerprint,
            failureMessage = failureMessage,
            status = status,
        )

    private fun PrivilegeAdbAuthorizationStatus.toPairingCheckFailureStatus(): PrivilegeAdbPairingCheckStatus =
        when (this) {
            PrivilegeAdbAuthorizationStatus.AUTHORIZED -> PrivilegeAdbPairingCheckStatus.PAIRED
            PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> PrivilegeAdbPairingCheckStatus.UNPAIRED
            PrivilegeAdbAuthorizationStatus.UNAVAILABLE -> PrivilegeAdbPairingCheckStatus.UNAVAILABLE
            PrivilegeAdbAuthorizationStatus.ERROR -> PrivilegeAdbPairingCheckStatus.ERROR
        }

    private data class PortResolution(
        val port: Int?,
        val failureMessage: String = "ADB connect port is not available",
    )
}
