package priv.kit.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import priv.kit.internal.core.PrivilegeServerLaunchCommand
import priv.kit.PrivilegeStartupException
import priv.kit.PrivilegeStartupLogListener
import priv.kit.internal.runtime.PrivilegeContext
import java.io.Closeable
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public class PrivilegeAdbStarter private constructor(
    private val identity: PrivilegeAdbIdentity,
    private val loadKeyBytes: () -> ByteArray,
    private val nsdManagerProvider: () -> NsdManager,
) {
    @Throws(PrivilegeStartupException::class)
    internal fun start(
        command: PrivilegeServerLaunchCommand,
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
    ): PrivilegeAdbStartResult =
        start(
            command = command,
            options = options,
            startupLogListener = null,
        )

    @Throws(PrivilegeStartupException::class)
    internal fun start(
        command: PrivilegeServerLaunchCommand,
        options: PrivilegeAdbStartOptions,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeAdbStartResult {
        val output = PrivilegeAdbOutput(startupLogListener)
        return try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            output.append(
                "diag",
                "Launch command main=${command.mainClass}, provider=${command.providerAuthority}, " +
                    "classpathEntries=${command.classpath.split(':').size}, commandLength=${command.commandLine.length}",
            )
            val activeTcpPort = PrivilegeAdbEnvironment.getAdbTcpPort()
            output.append(
                "diag",
                "Port selection explicit=${options.port}, discover=${options.discoverPort}, " +
                    "tcpMode=${options.tcpMode}, activeTcp=$activeTcpPort, targetTcp=${options.tcpPort}",
            )
            val shouldDiscoverPort = options.port == null &&
                !(options.tcpMode && activeTcpPort > 0) &&
                options.discoverPort
            var activePort = PrivilegeAdbPortSelector.chooseStartPort(
                explicitPort = options.port,
                activeTcpPort = activeTcpPort,
                tcpMode = options.tcpMode,
                targetTcpPort = options.tcpPort,
                discoveredPort = if (shouldDiscoverPort) {
                    discoverConnectPort(options.portDiscoveryTimeoutMillis)
                } else {
                    null
                },
            )

            if (options.tcpMode && activePort != options.tcpPort) {
                output.append("adb", "Switching ADB to TCP port ${options.tcpPort}")
                PrivilegeAdbClient(activePort, key).use { client ->
                    connectWithRetry(client, options, output)
                    runCatching {
                        client.command("tcpip:${options.tcpPort}", output)
                    }.onFailure { throwable ->
                        if (throwable !is EOFException && throwable !is SocketException) {
                            throw throwable
                        }
                    }
                }
                activePort = options.tcpPort
            }

            PrivilegeAdbClient(activePort, key).use { client ->
                connectWithRetry(client, options, output)
                client.command("shell:${command.commandLine}", output)
            }

            PrivilegeAdbStartResult(
                port = activePort,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to start Privileged Server with ADB: ${output.text()}", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun getIdentityInfo(): PrivilegeAdbIdentityInfo =
        try {
            val key = createKey()
            PrivilegeAdbIdentityInfo(
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to load ADB identity", throwable)
        }

    public fun getActiveTcpPort(): Int? =
        PrivilegeAdbEnvironment.getAdbTcpPort().takeIf { it > 0 }

    @Throws(PrivilegeStartupException::class)
    public fun checkPairing(
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingCheckResult {
        return openPairingCheckSession(
            port = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
        ).use { session ->
            session.check()
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun openPairingCheckSession(
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingCheckSession {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
        }
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        val key = try {
            createKey()
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to check wireless ADB pairing", throwable)
        }
        return PrivilegeAdbPairingCheckSession(
            identity = identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
            explicitPort = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
            discoverConnectPort = ::discoverConnectPort,
            clientFactory = { activePort -> PrivilegeAdbClient(activePort, key) },
        )
    }

    internal fun readRuntimeDiagnostics(
        port: Int,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        require(port in 1..65535) { "port must be between 1 and 65535" }

        val output = PrivilegeAdbOutput(startupLogListener)
        appendRuntimeDiagnostics(
            port = port,
            key = createKey(),
            output = output,
        )
        return output.text()
    }

    @Throws(PrivilegeStartupException::class)
    public fun pair(
        port: Int,
        pairingCode: String,
    ): PrivilegeAdbPairingResult = pair(
        pairingCode = pairingCode,
        port = port,
        discoverPort = false,
    )

    @Throws(PrivilegeStartupException::class)
    public fun pair(
        pairingCode: String,
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        val normalizedPairingCode = pairingCode.toPrivilegeAdbPairingCode()
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(normalizedPairingCode.isNotBlank()) { "pairingCode must contain digits" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        return try {
            val key = createKey()
            val activePort = port ?: if (discoverPort) {
                discoverPairingPort(portDiscoveryTimeoutMillis)
            } else {
                throw PrivilegeAdbException("ADB pairing port is not available")
            }
            PrivilegeAdbPairingClient(activePort, normalizedPairingCode, key).use { client ->
                if (!client.start()) {
                    throw PrivilegeAdbException("ADB pairing failed")
                }
            }
            PrivilegeAdbPairingResult(
                port = activePort,
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to pair with wireless ADB", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun discoverPairingPort(timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_PAIRING).use { it.discoverPort(timeoutMillis) }
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB pairing port", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun discoverConnectPort(timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_CONNECT).use { it.discoverPort(timeoutMillis) }
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB connect port", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun switchToTcp(
        currentPort: Int? = null,
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpResult {
        require(currentPort == null || currentPort in 1..65535) { "currentPort must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        return try {
            val activeTcpPort = getActiveTcpPort()
            output.append(
                "diag",
                "ADB TCP switch currentPort=$currentPort, activeTcp=$activeTcpPort, targetTcp=$tcpPort",
            )
            if (activeTcpPort == tcpPort) {
                output.append("adb", "ADB TCP port $tcpPort is already active")
                return PrivilegeAdbTcpResult(
                    port = tcpPort,
                    outputText = output.text(),
                    identity = identity,
                )
            }

            val connectPort = currentPort ?: activeTcpPort ?: discoverConnectPort()
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(connectPort, key).use { client ->
                client.connect(output)
                runCatching {
                    client.command("tcpip:$tcpPort", output)
                }.onFailure { throwable ->
                    if (throwable !is EOFException && throwable !is SocketException) {
                        throw throwable
                    }
                }
            }
            PrivilegeAdbTcpResult(
                port = tcpPort,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeStartupException("Failed to switch ADB to TCP mode: ${output.text()}", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun stopTcp(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpResult {
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        return try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(tcpPort, key).use { client ->
                client.connect(output)
                client.command("usb:", output)
            }
            PrivilegeAdbTcpResult(
                port = tcpPort,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeStartupException("Failed to stop ADB TCP mode: ${output.text()}", throwable)
        }
    }

    public fun checkTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbAuthorizationCheckResult {
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        val key = runCatching { createKey() }.getOrElse { throwable ->
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB TCP authorization check failed: $failureMessage")
            return PrivilegeAdbAuthorizationCheckResult(
                status = PrivilegeAdbAuthorizationStatus.ERROR,
                outputText = output.text(),
                identity = identity,
                failureMessage = failureMessage,
            )
        }
        output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
        output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
        return try {
            val status = PrivilegeAdbClient(tcpPort, key).use { client ->
                client.checkAuthorization(output)
            }
            PrivilegeAdbAuthorizationCheckResult(
                status = status,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            val status = if (throwable is ConnectException || throwable is SocketTimeoutException) {
                PrivilegeAdbAuthorizationStatus.UNAVAILABLE
            } else {
                PrivilegeAdbAuthorizationStatus.ERROR
            }
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB TCP authorization check failed: $failureMessage")
            PrivilegeAdbAuthorizationCheckResult(
                status = status,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
                failureMessage = failureMessage,
            )
        }
    }

    public fun requestTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
        timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
        callback: PrivilegeAdbAuthorizationRequestCallback,
    ): Closeable {
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(timeoutMillis <= Int.MAX_VALUE) { "timeoutMillis must be at most ${Int.MAX_VALUE}" }

        val output = PrivilegeAdbOutput()
        val key = runCatching { createKey() }.getOrElse { throwable ->
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB TCP authorization request failed: $failureMessage")
            callback.onResult(
                PrivilegeAdbAuthorizationRequestResult(
                    authorized = false,
                    endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
                    outputText = output.text(),
                    failureMessage = failureMessage,
                ),
            )
            return Closeable {}
        }
        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val clientRef = AtomicReference<PrivilegeAdbClient?>()

        fun complete(result: PrivilegeAdbAuthorizationRequestResult) {
            if (completed.compareAndSet(false, true)) {
                callback.onResult(result)
            }
        }

        val thread = Thread {
            if (completed.get()) return@Thread
            try {
                output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
                output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
                PrivilegeAdbClient(
                    port = tcpPort,
                    key = key,
                    socketReadTimeoutMillis = timeoutMillis.toInt(),
                ).use { client ->
                    clientRef.set(client)
                    if (completed.get()) return@use
                    val status = client.requestAuthorization(output)
                    if (status == PrivilegeAdbAuthorizationStatus.AUTHORIZED) {
                        complete(
                            PrivilegeAdbAuthorizationRequestResult(
                                authorized = true,
                                outputText = output.text(),
                            ),
                        )
                    } else {
                        complete(
                            PrivilegeAdbAuthorizationRequestResult(
                                authorized = false,
                                endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
                                outputText = output.text(),
                                failureMessage = "ADB key authorization did not complete",
                            ),
                        )
                    }
                }
            } catch (throwable: Throwable) {
                if (cancelled.get()) return@Thread
                val timedOut = throwable is SocketTimeoutException
                complete(
                    PrivilegeAdbAuthorizationRequestResult(
                        authorized = false,
                        endReason = if (timedOut) {
                            PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT
                        } else {
                            PrivilegeAdbAuthorizationEndReason.FAILED
                        },
                        outputText = output.text(),
                        failureMessage = if (timedOut) null else throwable.toFailureMessage(),
                    ),
                )
            } finally {
                clientRef.set(null)
            }
        }.apply {
            name = "priv-adb-tcp-authorization"
            isDaemon = true
            start()
        }

        return Closeable {
            if (completed.compareAndSet(false, true)) {
                cancelled.set(true)
                clientRef.getAndSet(null)?.close()
                thread.interrupt()
                callback.onResult(
                    PrivilegeAdbAuthorizationRequestResult(
                        authorized = false,
                        endReason = PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
                        outputText = output.text(),
                    ),
                )
            }
        }
    }

    private fun createKey(): PrivilegeAdbKey =
        try {
            PrivilegeAdbKey(
                keyBytes = loadKeyBytes(),
                name = identity.adbDeviceName,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeAdbException("Failed to load ADB key", throwable)
        }

    private fun runDiagnosticShellCommand(
        port: Int,
        key: PrivilegeAdbKey,
        label: String,
        command: String,
        output: PrivilegeAdbOutput,
    ) {
        runCatching {
            PrivilegeAdbClient(port, key).use { client ->
                client.connect(output)
                client.command("shell:$command", output)
            }
        }.onFailure { throwable ->
            output.append(
                "diag",
                "Failed to read $label: ${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
    }

    private fun appendRuntimeDiagnostics(
        port: Int,
        key: PrivilegeAdbKey,
        output: PrivilegeAdbOutput,
    ) {
        output.append("diag", "Reading server process state")
        runDiagnosticShellCommand(
            port = port,
            key = key,
            label = "server process state",
            command = "ps -A | grep '[p]riv-kit-server' 2>&1 || true",
            output = output,
        )
        output.append("diag", "Reading PrivKit logcat")
        runDiagnosticShellCommand(
            port = port,
            key = key,
            label = "PrivKit logcat",
            command = "logcat -d -t 160 -s PrivKitServer:D PrivKit:D AndroidRuntime:E '*:S' 2>&1",
            output = output,
        )
    }

    private fun connectWithRetry(
        client: PrivilegeAdbClient,
        options: PrivilegeAdbStartOptions,
        output: PrivilegeAdbOutput,
    ) {
        var nextDelay = 0L
        for (attempt in 1..options.connectRetryCount) {
            try {
                if (nextDelay > 0L) {
                    Thread.sleep(nextDelay)
                }
                output.append("diag", "ADB connect attempt $attempt/${options.connectRetryCount}")
                client.connect(output)
                return
            } catch (throwable: Throwable) {
                output.append("diag", "ADB connect attempt $attempt failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                if (attempt == options.connectRetryCount || throwable is SocketTimeoutException) {
                    throw throwable
                }
                nextDelay = options.connectRetryDelayMillis
            }
        }
    }

    internal companion object {
        internal fun forOwnerToken(
            ownerToken: String,
            adbDeviceName: String = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbStarter =
            PrivilegeContext.require().let { applicationContext ->
                PrivilegeAdbStarter(
                    identity = PrivilegeAdbIdentity.forOwnerToken(
                        ownerToken = ownerToken,
                        deviceName = adbDeviceName,
                    ),
                    loadKeyBytes = { PrivilegeAdbKeyStore.readOrCreate() },
                    nsdManagerProvider = { requireNsdManager(applicationContext) },
                )
            }

        private fun requireNsdManager(context: Context): NsdManager =
            context.getSystemService(NsdManager::class.java)
                ?: throw PrivilegeStartupException("NSD manager is unavailable")
    }
}

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

internal fun String.toPrivilegeAdbPairingCode(): String =
    filter(Char::isDigit)
        .take(ADB_PAIRING_CODE_LENGTH)

private fun Throwable.toFailureMessage(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".trim()

private const val ADB_PAIRING_CODE_LENGTH = 6
