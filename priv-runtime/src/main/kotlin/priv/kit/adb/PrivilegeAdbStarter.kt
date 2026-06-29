package priv.kit.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import priv.kit.internal.core.PrivilegeServerLaunchCommand
import priv.kit.PrivilegeStartupException
import priv.kit.PrivilegeStartupLogListener
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

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
                PrivilegeAdbClient(options.host, activePort, key).use { client ->
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

            PrivilegeAdbClient(options.host, activePort, key).use { client ->
                connectWithRetry(client, options, output)
                client.command("shell:${command.commandLine}", output)
            }

            PrivilegeAdbStartResult(
                host = options.host,
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

    public fun getIdentityInfo(): PrivilegeAdbIdentityInfo {
        val key = createKey()
        return PrivilegeAdbIdentityInfo(
            identity = identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
        )
    }

    public fun getActiveTcpPort(): Int? =
        PrivilegeAdbEnvironment.getAdbTcpPort().takeIf { it > 0 }

    @Throws(PrivilegeStartupException::class)
    public fun checkPairing(
        port: Int? = null,
        host: String = DEFAULT_ADB_HOST,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingCheckResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
        }
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        val output = PrivilegeAdbOutput()
        val key = createKey()
        output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
        output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
        output.append(
            "diag",
            "Pairing check explicit=$port, discover=$discoverPort",
        )

        val activePort = port ?: if (discoverPort) {
            runCatching {
                discoverConnectPort(portDiscoveryTimeoutMillis)
            }.getOrElse { throwable ->
                val failureMessage = throwable.toFailureMessage()
                output.append("diag", "ADB pairing check failed before connect: $failureMessage")
                return PrivilegeAdbPairingCheckResult(
                    host = host,
                    port = null,
                    paired = false,
                    outputText = output.text(),
                    identity = identity,
                    publicKeyFingerprint = key.adbPublicKeyFingerprint,
                    failureMessage = failureMessage,
                )
            }
        } else {
            output.append("diag", "ADB pairing check skipped because no connect port is available")
            return PrivilegeAdbPairingCheckResult(
                host = host,
                port = null,
                paired = false,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
                failureMessage = "ADB connect port is not available",
            )
        }

        return try {
            PrivilegeAdbClient(host, activePort, key).use { client ->
                client.connect(output)
            }
            output.append("diag", "ADB pairing check succeeded on $host:$activePort")
            PrivilegeAdbPairingCheckResult(
                host = host,
                port = activePort,
                paired = true,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB pairing check failed on $host:$activePort: $failureMessage")
            PrivilegeAdbPairingCheckResult(
                host = host,
                port = activePort,
                paired = false,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
                failureMessage = failureMessage,
            )
        }
    }

    internal fun readRuntimeDiagnostics(
        host: String,
        port: Int,
    ): String =
        readRuntimeDiagnostics(
            host = host,
            port = port,
            startupLogListener = null,
        )

    internal fun readRuntimeDiagnostics(
        host: String,
        port: Int,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }

        val output = PrivilegeAdbOutput(startupLogListener)
        appendRuntimeDiagnostics(
            host = host,
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
        host: String = DEFAULT_ADB_HOST,
    ): PrivilegeAdbPairingResult = pair(
        pairingCode = pairingCode,
        port = port,
        host = host,
        discoverPort = false,
    )

    @Throws(PrivilegeStartupException::class)
    public fun pair(
        pairingCode: String,
        port: Int? = null,
        host: String = DEFAULT_ADB_HOST,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        val normalizedPairingCode = pairingCode.toPrivilegeAdbPairingCode()
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(normalizedPairingCode.isNotBlank()) { "pairingCode must contain digits" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        return try {
            val key = createKey()
            val activePort = port ?: if (discoverPort) {
                discoverPairingPort(portDiscoveryTimeoutMillis)
            } else {
                throw PrivilegeAdbException("ADB pairing port is not available")
            }
            PrivilegeAdbPairingClient(host, activePort, normalizedPairingCode, key).use { client ->
                if (!client.start()) {
                    throw PrivilegeAdbException("ADB pairing failed")
                }
            }
            PrivilegeAdbPairingResult(
                host = host,
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
    public fun discoverPairingPort(timeoutMillis: Long = DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
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
    public fun discoverConnectPort(timeoutMillis: Long = DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
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
        currentPort: Int,
        tcpPort: Int = DEFAULT_ADB_TCP_PORT,
        host: String = DEFAULT_ADB_HOST,
    ): PrivilegeAdbTcpResult {
        require(currentPort in 1..65535) { "currentPort must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(host.isNotBlank()) { "host must not be blank" }
        val output = PrivilegeAdbOutput()
        return try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(host, currentPort, key).use { client ->
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
                host = host,
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
        tcpPort: Int = DEFAULT_ADB_TCP_PORT,
        host: String = DEFAULT_ADB_HOST,
    ): PrivilegeAdbTcpResult {
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(host.isNotBlank()) { "host must not be blank" }
        val output = PrivilegeAdbOutput()
        return try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(host, tcpPort, key).use { client ->
                client.connect(output)
                client.command("usb:", output)
            }
            PrivilegeAdbTcpResult(
                host = host,
                port = tcpPort,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeStartupException("Failed to stop ADB TCP mode: ${output.text()}", throwable)
        }
    }

    private fun createKey(): PrivilegeAdbKey =
        try {
            PrivilegeAdbKey(
                keyBytes = loadKeyBytes(),
                name = identity.adbDeviceName,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeAdbKeyException(throwable)
        }

    private fun runDiagnosticShellCommand(
        host: String,
        port: Int,
        key: PrivilegeAdbKey,
        label: String,
        command: String,
        output: PrivilegeAdbOutput,
    ) {
        runCatching {
            PrivilegeAdbClient(host, port, key).use { client ->
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
        host: String,
        port: Int,
        key: PrivilegeAdbKey,
        output: PrivilegeAdbOutput,
    ) {
        output.append("diag", "Reading server process state")
        runDiagnosticShellCommand(
            host = host,
            port = port,
            key = key,
            label = "server process state",
            command = "ps -A | grep '[p]riv-kit-server' 2>&1 || true",
            output = output,
        )
        output.append("diag", "Reading PrivKit logcat")
        runDiagnosticShellCommand(
            host = host,
            port = port,
            key = key,
            label = "PrivKit logcat",
            command = "logcat -d -t 160 -s PrivKitServer:D PrivKitRuntime:D AndroidRuntime:E '*:S' 2>&1",
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

    public companion object {
        internal fun forOwnerToken(
            ownerToken: String,
            context: Context,
            adbDeviceName: String = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbStarter =
            context.applicationContext.let { applicationContext ->
                PrivilegeAdbStarter(
                    identity = PrivilegeAdbIdentity.forOwnerToken(
                        ownerToken = ownerToken,
                        deviceName = adbDeviceName,
                    ),
                    loadKeyBytes = { PrivilegeAdbKeyStore.readOrCreate(applicationContext) },
                    nsdManagerProvider = { requireNsdManager(applicationContext) },
                )
            }

        private fun requireNsdManager(context: Context): NsdManager =
            context.getSystemService(NsdManager::class.java)
                ?: throw PrivilegeStartupException("NSD manager is unavailable")
    }
}

internal fun String.toPrivilegeAdbPairingCode(): String =
    filter(Char::isDigit)
        .take(ADB_PAIRING_CODE_LENGTH)

private fun Throwable.toFailureMessage(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".trim()

private const val DEFAULT_ADB_HOST: String = "127.0.0.1"
private const val DEFAULT_ADB_TCP_PORT: Int = 5555
private const val DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS: Long = 15_000L
private const val ADB_PAIRING_CODE_LENGTH = 6
