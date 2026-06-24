package priv.kit.adb

import android.content.Context
import android.os.Build
import priv.kit.core.PrivilegeStartupException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

public class PrivilegeAdbStarter private constructor(
    private val context: Context,
    private val identity: PrivilegeAdbIdentity,
) {
    private val keyStore: PrivilegeAdbKeyStore = PrivilegeAdbFileKeyStore.create(context)

    public constructor(
        context: Context,
        adbDeviceName: String? = null,
    ) : this(
        context = context.applicationContext,
        identity = PrivilegeAdbIdentity.default(resolveDeviceName(context, adbDeviceName)),
    )

    @Throws(PrivilegeStartupException::class)
    public fun start(
        command: PrivilegeAdbCommand,
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
    ): PrivilegeAdbStartResult {
        val output = PrivilegeAdbOutput()
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
                command = command,
                host = options.host,
                port = activePort,
                output = output,
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

    @Throws(PrivilegeStartupException::class)
    public fun checkPairing(
        port: Int? = null,
        host: String = PrivilegeAdbStartOptions.DEFAULT_HOST,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PrivilegeAdbStartOptions.DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
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
                    output = output,
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
                output = output,
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
                output = output,
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
                output = output,
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
                failureMessage = failureMessage,
            )
        }
    }

    public fun readDiagnosticLog(
        host: String,
        port: Int,
        path: String,
    ): PrivilegeAdbOutput {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(path.startsWith(DIAGNOSTIC_LOG_PREFIX)) { "unsupported diagnostic log path" }

        val output = PrivilegeAdbOutput()
        val key = createKey()
        output.append("diag", "Reading server diagnostic log: $path")
        runDiagnosticShellCommand(
            host = host,
            port = port,
            key = key,
            label = "server stdout/stderr",
            command = "cat ${path.shellQuote()} 2>&1",
            output = output,
        )
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
        return output
    }

    @Throws(PrivilegeStartupException::class)
    public fun pair(
        port: Int,
        pairingCode: String,
        host: String = PrivilegeAdbStartOptions.DEFAULT_HOST,
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
        host: String = PrivilegeAdbStartOptions.DEFAULT_HOST,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PrivilegeAdbStartOptions.DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
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
    public fun discoverPairingPort(timeoutMillis: Long = PrivilegeAdbStartOptions.DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(context, PrivilegeAdbMdns.TLS_PAIRING).use { it.discoverPort(timeoutMillis) }
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB pairing port", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun discoverConnectPort(timeoutMillis: Long = PrivilegeAdbStartOptions.DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(context, PrivilegeAdbMdns.TLS_CONNECT).use { it.discoverPort(timeoutMillis) }
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB connect port", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun switchToTcp(
        currentPort: Int,
        tcpPort: Int = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT,
        host: String = PrivilegeAdbStartOptions.DEFAULT_HOST,
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
                output = output,
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeStartupException("Failed to switch ADB to TCP mode: ${output.text()}", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun stopTcp(
        tcpPort: Int = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT,
        host: String = PrivilegeAdbStartOptions.DEFAULT_HOST,
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
                output = output,
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throw PrivilegeStartupException("Failed to stop ADB TCP mode: ${output.text()}", throwable)
        }
    }

    private fun createKey(): PrivilegeAdbKey =
        try {
            PrivilegeAdbKey(keyStore, identity.adbDeviceName)
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
        public const val DIAGNOSTIC_LOG_PREFIX: String = "/data/local/tmp/priv-kit-server-"

        public fun forOwnerToken(
            context: Context,
            ownerToken: String,
            adbDeviceName: String? = null,
        ): PrivilegeAdbStarter =
            PrivilegeAdbStarter(
                context = context.applicationContext,
                identity = PrivilegeAdbIdentity.forOwnerToken(
                    ownerToken = ownerToken,
                    deviceName = resolveDeviceName(context, adbDeviceName),
                ),
            )

        private fun resolveDeviceName(
            context: Context,
            adbDeviceName: String?,
        ): String {
            val requestedName = adbDeviceName?.trim()
            if (!requestedName.isNullOrEmpty()) return requestedName

            val applicationContext = context.applicationContext
            val appLabel = runCatching {
                applicationContext.applicationInfo
                    .loadLabel(applicationContext.packageManager)
                    .toString()
            }.getOrNull()
            return appLabel.toSafeDefaultDeviceName()
                ?: applicationContext.packageName.toSafeDefaultDeviceName()
                ?: PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME
        }

        private fun String?.toSafeDefaultDeviceName(): String? {
            val value = this
                ?.replace('\u0000', ' ')
                ?.replace('\r', ' ')
                ?.replace('\n', ' ')
                ?.trim()
                ?.take(PrivilegeAdbIdentity.MAX_DEVICE_NAME_LENGTH)
            return value?.ifBlank { null }
        }
    }
}

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

internal fun String.toPrivilegeAdbPairingCode(): String =
    filter(Char::isDigit)
        .take(ADB_PAIRING_CODE_LENGTH)

private fun Throwable.toFailureMessage(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".trim()

private const val ADB_PAIRING_CODE_LENGTH = 6
