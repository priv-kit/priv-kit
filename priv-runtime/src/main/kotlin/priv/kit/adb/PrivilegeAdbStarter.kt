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
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public class PrivilegeAdbStarter private constructor(
    private val identity: PrivilegeAdbIdentity,
    private val loadKeyBytes: () -> ByteArray,
    private val nsdManagerProvider: () -> NsdManager,
    private val wirelessDebuggingControllerProvider: () -> PrivilegeAdbWirelessDebuggingController,
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
        var managedWirelessDebuggingController: PrivilegeAdbWirelessDebuggingController? = null
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
                    "tcpMode=${options.tcpMode}, activeTcp=$activeTcpPort, targetTcp=${options.tcpPort}, " +
                    "wirelessControl=${options.wirelessDebuggingControl}",
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
                    discoverConnectPortForStart(options, output) { controller ->
                        managedWirelessDebuggingController = controller
                    }
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
        } finally {
            if (options.disableWirelessDebuggingAfterStart) {
                managedWirelessDebuggingController?.let { controller ->
                    runCatching {
                        controller.setWirelessDebuggingEnabled(false)
                        output.append("adb", "Wireless debugging disabled")
                    }.onFailure { throwable ->
                        output.append("diag", "Failed to disable Wireless debugging: ${throwable.toFailureMessage()}")
                    }
                }
            }
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

    public fun getWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PrivilegeAdbWirelessDebuggingControlStatus(
                supported = false,
                permissionDeclared = false,
                permissionGranted = false,
                wirelessDebuggingEnabled = false,
                canManage = false,
                failureMessage = "Wireless ADB requires Android 11 or above",
            )
        } else {
            wirelessDebuggingControllerProvider().status()
        }

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

    private fun discoverConnectPortForStart(
        options: PrivilegeAdbStartOptions,
        output: PrivilegeAdbOutput,
        onManagedWirelessDebugging: (PrivilegeAdbWirelessDebuggingController) -> Unit,
    ): Int {
        val controller = wirelessDebuggingControllerProvider()
        val status = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PrivilegeAdbWirelessDebuggingControlStatus(
                supported = false,
                permissionDeclared = false,
                permissionGranted = false,
                wirelessDebuggingEnabled = false,
                canManage = false,
                failureMessage = "Wireless ADB requires Android 11 or above",
            )
        } else {
            controller.status()
        }
        output.append(
            "diag",
            "Wireless debugging control supported=${status.supported}, " +
                "declared=${status.permissionDeclared}, permission=${status.permissionGranted}, " +
                "enabled=${status.wirelessDebuggingEnabled}",
        )
        if (options.wirelessDebuggingControl != PrivilegeAdbWirelessDebuggingControl.NEVER && status.canManage) {
            output.append("adb", "Temporarily enabling Wireless debugging")
            controller.prepareAdb()
            controller.setWirelessDebuggingEnabled(true)
            onManagedWirelessDebugging(controller)
        } else if (options.wirelessDebuggingControl == PrivilegeAdbWirelessDebuggingControl.REQUIRE) {
            throw PrivilegeAdbException(
                status.failureMessage ?: if (!status.permissionDeclared) {
                    "WRITE_SECURE_SETTINGS must be declared to manage Wireless debugging"
                } else {
                    "WRITE_SECURE_SETTINGS is required to manage Wireless debugging"
                },
            )
        }
        return discoverConnectPort(options.portDiscoveryTimeoutMillis)
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
        return try {
            openTcpAuthorizationCheckSession(tcpPort).use { session ->
                session.check()
            }
        } catch (throwable: Throwable) {
            tcpAuthorizationKeyFailureResult(throwable)
        }
    }

    public fun openTcpAuthorizationCheckSession(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpAuthorizationCheckSession {
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        val key = try {
            createKey()
        } catch (throwable: Throwable) {
            if (throwable is PrivilegeStartupException || throwable is PrivilegeAdbException) throw throwable
            throw PrivilegeAdbException("Failed to load ADB key", throwable)
        }
        return PrivilegeAdbTcpAuthorizationCheckSession(
            identity = identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
            tcpPort = tcpPort,
            clientFactory = { PrivilegeAdbClient(tcpPort, key) },
        )
    }

    private fun tcpAuthorizationKeyFailureResult(throwable: Throwable): PrivilegeAdbAuthorizationCheckResult {
        val output = PrivilegeAdbOutput()
        val failureMessage = throwable.toFailureMessage()
        output.append("diag", "ADB TCP authorization check failed: $failureMessage")
        return throwable.toTcpAuthorizationCheckResult(
            output = output,
            identity = identity,
            publicKeyFingerprint = "",
        )
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
                    wirelessDebuggingControllerProvider = {
                        AndroidPrivilegeAdbWirelessDebuggingController(applicationContext)
                    },
                )
            }

        private fun requireNsdManager(context: Context): NsdManager =
            context.getSystemService(NsdManager::class.java)
                ?: throw PrivilegeStartupException("NSD manager is unavailable")
    }
}
