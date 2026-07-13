package priv.kit.adb

import java.io.Closeable

public class PrivilegeAdbPairingCheckSession internal constructor(
    private val identity: PrivilegeAdbIdentity,
    private val publicKeyFingerprint: String,
    private val explicitPort: Int?,
    private val discoverPort: Boolean,
    private val portDiscoveryTimeoutMillis: Long,
    private val discoverConnectEndpoint: (Long) -> PrivilegeAdbEndpoint,
    private val clientFactory: (PrivilegeAdbEndpoint) -> PrivilegeAdbAuthorizationConnection,
) : Closeable {
    private val lock = Any()

    @Volatile
    private var closed = false
    private var client: PrivilegeAdbAuthorizationConnection? = null
    private var connectedEndpoint: PrivilegeAdbEndpoint? = null

    public val port: Int?
        get() = synchronized(lock) {
            connectedEndpoint?.port ?: explicitPort
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

        val endpointResolution = resolveEndpoint(output)
        return endpointResolution.endpoint?.let { activeEndpoint ->
            connectNewClient(activeEndpoint, output)
        } ?: failureResult(
            port = null,
            output = output,
            failureMessage = endpointResolution.failureMessage,
            status = PrivilegeAdbPairingCheckStatus.UNAVAILABLE,
        )
    }

    override fun close() {
        closed = true
        val clientToClose = synchronized(lock) {
            val currentClient = client
            client = null
            connectedEndpoint = null
            currentClient
        }
        clientToClose?.close()
    }

    private fun checkExistingConnection(output: PrivilegeAdbOutput): PrivilegeAdbPairingCheckResult? {
        val (activeClient, activeEndpoint) = synchronized(lock) {
            val currentClient = client ?: return null
            val currentEndpoint = connectedEndpoint ?: return null
            currentClient to currentEndpoint
        }
        // Keep the transport alive between polling ticks. Some Android 13 builds stop
        // accepting wireless ADB after a connect-and-disconnect probe while Settings
        // still shows Wireless Debugging as enabled.
        output.append(
            "diag",
            "Reusing ADB pairing check connection on $activeEndpoint",
        )
        return try {
            activeClient.keepAlive(output)
            output.append("diag", "ADB pairing check connection is still alive")
            successResult(
                port = activeEndpoint.port,
                output = output,
            )
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            output.append(
                "diag",
                "ADB pairing check connection failed on $activeEndpoint: $failureMessage",
            )
            closeClient(activeClient)
            null
        }
    }

    private fun resolveEndpoint(output: PrivilegeAdbOutput): EndpointResolution {
        explicitPort?.let { return EndpointResolution(endpoint = PrivilegeAdbEndpoint.local(it)) }
        if (!discoverPort) {
            output.append("diag", "ADB pairing check skipped because no connect port is available")
            return EndpointResolution(
                endpoint = null,
                failureMessage = "ADB connect port is not available",
            )
        }
        return runCatching {
            discoverConnectEndpoint(portDiscoveryTimeoutMillis)
        }.fold(
            onSuccess = { endpoint -> EndpointResolution(endpoint = endpoint) },
            onFailure = { throwable ->
                val failureMessage = throwable.toFailureMessage()
                output.append("diag", "ADB pairing check failed before connect: $failureMessage")
                EndpointResolution(
                    endpoint = null,
                    failureMessage = failureMessage,
                )
            },
        )
    }

    private fun connectNewClient(
        activeEndpoint: PrivilegeAdbEndpoint,
        output: PrivilegeAdbOutput,
    ): PrivilegeAdbPairingCheckResult {
        val newClient = clientFactory(activeEndpoint)
        if (!setClient(newClient, activeEndpoint)) {
            newClient.close()
            return failureResult(
                port = activeEndpoint.port,
                output = output,
                failureMessage = "ADB pairing check session is closed",
                status = PrivilegeAdbPairingCheckStatus.ERROR,
            )
        }
        return try {
            output.append(
                "diag",
                "Opening persistent ADB pairing check connection on $activeEndpoint",
            )
            when (val status = newClient.checkAuthorization(output)) {
                PrivilegeAdbAuthorizationStatus.AUTHORIZED -> {
                    output.append("diag", "ADB pairing check succeeded on $activeEndpoint")
                    successResult(
                        port = activeEndpoint.port,
                        output = output,
                    )
                }
                PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> {
                    output.append("diag", "ADB pairing check found unauthorized key on $activeEndpoint")
                    closeClient(newClient)
                    failureResult(
                        port = activeEndpoint.port,
                        output = output,
                        failureMessage = "ADB key is not authorized",
                        status = PrivilegeAdbPairingCheckStatus.UNPAIRED,
                    )
                }
                PrivilegeAdbAuthorizationStatus.UNAVAILABLE,
                PrivilegeAdbAuthorizationStatus.ERROR,
                -> {
                    output.append("diag", "ADB pairing check returned $status on $activeEndpoint")
                    closeClient(newClient)
                    failureResult(
                        port = activeEndpoint.port,
                        output = output,
                        failureMessage = "ADB pairing check returned $status",
                        status = status.toPairingCheckFailureStatus(),
                    )
                }
            }
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            val status = throwable.toPairingCheckFailureStatus()
            output.append("diag", "ADB pairing check failed on $activeEndpoint: $failureMessage")
            closeClient(newClient)
            failureResult(
                port = activeEndpoint.port,
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
        activeEndpoint: PrivilegeAdbEndpoint,
    ): Boolean =
        synchronized(lock) {
            if (closed) {
                false
            } else {
                client = newClient
                connectedEndpoint = activeEndpoint
                true
            }
        }

    private fun closeClient(expected: PrivilegeAdbAuthorizationConnection) {
        val clientToClose = synchronized(lock) {
            if (client === expected) {
                client = null
                connectedEndpoint = null
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

    private data class EndpointResolution(
        val endpoint: PrivilegeAdbEndpoint?,
        val failureMessage: String = "ADB connect port is not available",
    )
}
