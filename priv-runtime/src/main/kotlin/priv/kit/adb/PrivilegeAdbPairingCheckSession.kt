package priv.kit.adb

import java.io.Closeable

public class PrivilegeAdbPairingCheckSession internal constructor(
    private val identity: PrivilegeAdbIdentity,
    private val publicKeyFingerprint: String,
    private val explicitPort: Int?,
    private val discoverPort: Boolean,
    private val portDiscoveryTimeoutMillis: Long,
    private val discoverConnectPort: (Long) -> Int,
    private val clientFactory: (Int) -> PrivilegeAdbConnection,
) : Closeable {
    private val lock = Any()

    @Volatile
    private var closed = false
    private var client: PrivilegeAdbConnection? = null
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
            )
        }

        checkExistingConnection(output)?.let { return it }
        if (closed) {
            return failureResult(
                port = port,
                output = output,
                failureMessage = "ADB pairing check session is closed",
            )
        }

        val portResolution = resolvePort(output)
        return portResolution.port?.let { activePort ->
            connectNewClient(activePort, output)
        } ?: failureResult(
            port = null,
            output = output,
            failureMessage = portResolution.failureMessage,
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
            )
        }
        return try {
            output.append(
                "diag",
                "Opening persistent ADB pairing check connection on $PRIVILEGE_ADB_LOCAL_HOST:$activePort",
            )
            newClient.connect(output)
            output.append("diag", "ADB pairing check succeeded on $PRIVILEGE_ADB_LOCAL_HOST:$activePort")
            successResult(
                port = activePort,
                output = output,
            )
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB pairing check failed on $PRIVILEGE_ADB_LOCAL_HOST:$activePort: $failureMessage")
            closeClient(newClient)
            failureResult(
                port = activePort,
                output = output,
                failureMessage = failureMessage,
            )
        }
    }

    private fun setClient(
        newClient: PrivilegeAdbConnection,
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

    private fun closeClient(expected: PrivilegeAdbConnection) {
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
        )

    private fun failureResult(
        port: Int?,
        output: PrivilegeAdbOutput,
        failureMessage: String,
    ): PrivilegeAdbPairingCheckResult =
        PrivilegeAdbPairingCheckResult(
            port = port,
            paired = false,
            outputText = output.text(),
            identity = identity,
            publicKeyFingerprint = publicKeyFingerprint,
            failureMessage = failureMessage,
        )

    private data class PortResolution(
        val port: Int?,
        val failureMessage: String = "ADB connect port is not available",
    )
}
