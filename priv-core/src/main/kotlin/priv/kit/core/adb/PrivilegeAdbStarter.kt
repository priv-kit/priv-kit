package priv.kit.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import priv.kit.core.internal.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.PrivilegeServerLaunchUncertainException
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS
import priv.kit.shared.isPrivilegeAdbPort
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

public class PrivilegeAdbStarter private constructor(
    private val identity: PrivilegeAdbIdentity,
    private val loadKeyBytes: () -> ByteArray,
    private val nsdManagerProvider: () -> NsdManager,
    private val wirelessDebuggingControllerProvider: () -> PrivilegeAdbWirelessDebuggingController,
) {
    @Throws(PrivilegeStartupException::class)
    internal suspend fun start(
        command: PrivilegeServerLaunchCommand,
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeAdbStartResult = withContext(Dispatchers.IO) {
        val output = PrivilegeAdbOutput(startupLogListener)
        var managedWirelessDebuggingController: PrivilegeAdbWirelessDebuggingController? = null
        try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            output.append(
                "diag",
                "Launch command main=${command.mainClass}, provider=${command.providerAuthority}, " +
                    "classpathEntries=${command.classpath.split(':').size}, commandLength=${command.commandLine.length}",
            )
            val activeTcpPort = PrivilegeAdbEnvironment.getActiveAdbTcpPort()
            output.append(
                "diag",
                "Port selection explicit=${options.port}, discover=${options.discoverPort}, " +
                    "tcpMode=${options.tcpMode}, activeTcp=$activeTcpPort, targetTcp=${options.tcpPort}, " +
                    "wirelessControl=${options.wirelessDebuggingControl}",
            )
            val shouldDiscoverEndpoint = options.port == null &&
                !(options.tcpMode && activeTcpPort > 0) &&
                options.discoverPort
            val activeEndpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
                explicitPort = options.port,
                activeTcpPort = activeTcpPort,
                tcpMode = options.tcpMode,
                targetTcpPort = options.tcpPort,
                discoveredEndpoint = if (shouldDiscoverEndpoint) {
                    discoverConnectEndpointForStart(options, output) { controller ->
                        managedWirelessDebuggingController = controller
                    }
                } else {
                    null
                },
            )
            output.append("diag", "Selected ADB command endpoint $activeEndpoint")

            PrivilegeAdbClient(activeEndpoint, key).cancellableUse { client ->
                connectWithRetry(client, activeEndpoint, options, output)
                output.append("diag", "Executing Privileged Server shell command on $activeEndpoint")
                try {
                    client.command("shell:${command.commandLine}", output)
                } catch (throwable: Throwable) {
                    throwable.rethrowIfInterrupted()
                    throw PrivilegeServerLaunchUncertainException(
                        "ADB server launch command may have been delivered on $activeEndpoint: ${output.text()}",
                        throwable,
                    )
                }
                output.append("diag", "Privileged Server shell command stream completed on $activeEndpoint")
            }

            PrivilegeAdbStartResult(
                endpoint = activeEndpoint,
                outputText = output.text(),
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to start Privileged Server with ADB: ${output.text()}", throwable)
        } finally {
            disableManagedWirelessDebuggingAfterStart(
                shouldDisable = options.disableWirelessDebuggingAfterStart,
                controller = managedWirelessDebuggingController,
                output = output,
            )
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
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to load ADB identity", throwable)
        }

    public fun getActiveTcpPort(): Int? =
        PrivilegeAdbEnvironment.getActiveAdbTcpPort().takeIf { it > 0 }

    public fun getConfiguredTcpPort(): Int? =
        PrivilegeAdbEnvironment.getConfiguredAdbTcpPort().takeIf { it > 0 }

    public suspend fun prepareTcpForStart(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbAuthorizationCheckResult {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val initialResult = checkTcpAuthorization(tcpPort = tcpPort)
        if (initialResult.status != PrivilegeAdbAuthorizationStatus.UNAVAILABLE) return initialResult

        val controller = wirelessDebuggingControllerProvider()
        val canManageAdb = runCatching { controller.status().canManage }.getOrElse { throwable ->
            throwable.rethrowIfInterrupted()
            false
        }
        return recoverTcpAuthorizationForStart(
            initialResult = initialResult,
            requestedTcpPort = tcpPort,
            configuredTcpPort = getConfiguredTcpPort(),
            canManageAdb = canManageAdb,
            enableAdb = controller::enableAdb,
            checkAuthorization = { checkTcpAuthorization(tcpPort = tcpPort) },
            sleep = { delayMillis -> delay(delayMillis.milliseconds) },
        )
    }

    public fun getWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            unsupportedWirelessDebuggingControlStatus()
        } else {
            wirelessDebuggingControllerProvider().status()
        }

    @Throws(PrivilegeStartupException::class)
    public suspend fun checkPairing(
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
        require(port == null || port.isPrivilegeAdbPort()) { "port must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        val key = try {
            createKey()
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to check wireless ADB pairing", throwable)
        }
        return PrivilegeAdbPairingCheckSession(
            identity = identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
            explicitPort = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
            discoverConnectEndpoint = ::discoverConnectEndpoint,
            clientFactory = { activeEndpoint -> PrivilegeAdbClient(activeEndpoint, key) },
        )
    }

    internal suspend fun readRuntimeDiagnostics(
        endpoint: PrivilegeAdbEndpoint,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        val output = PrivilegeAdbOutput(startupLogListener)
        appendRuntimeDiagnostics(
            endpoint = endpoint,
            key = createKey(),
            output = output,
        )
        return output.text()
    }

    @Throws(PrivilegeStartupException::class)
    public suspend fun pair(
        pairingCode: String,
        port: Int? = null,
        discoverPort: Boolean = true,
        portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbPairingResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        val normalizedPairingCode = pairingCode.toPrivilegeAdbPairingCode()
        require(port == null || port.isPrivilegeAdbPort()) { "port must be between 1 and 65535" }
        require(normalizedPairingCode.isNotBlank()) { "pairingCode must contain six ASCII digits" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        return try {
            val key = createKey()
            val activeEndpoint = port?.let(PrivilegeAdbEndpoint::local) ?: if (discoverPort) {
                discoverPairingEndpoint(portDiscoveryTimeoutMillis)
            } else {
                throw PrivilegeAdbException("ADB pairing port is not available")
            }
            PrivilegeAdbPairingClient(activeEndpoint, normalizedPairingCode, key).cancellableUse { client ->
                if (!client.start()) {
                    throw PrivilegeAdbException("ADB pairing failed")
                }
            }
            PrivilegeAdbPairingResult(
                port = activeEndpoint.port,
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to pair with wireless ADB", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public suspend fun discoverPairingPort(timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        return discoverPairingEndpoint(timeoutMillis).port
    }

    private suspend fun discoverPairingEndpoint(
        timeoutMillis: Long,
    ): PrivilegeAdbEndpoint {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB pairing requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_PAIRING)
                .discoverEndpoint(timeoutMillis)
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB pairing port", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    public suspend fun discoverConnectPort(timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS): Int {
        return discoverConnectEndpoint(timeoutMillis).port
    }

    private suspend fun discoverConnectEndpoint(
        timeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    ): PrivilegeAdbEndpoint {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
        }
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_CONNECT)
                .discoverEndpoint(timeoutMillis)
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB connect port", throwable)
        }
    }

    private suspend fun discoverConnectEndpointForStart(
        options: PrivilegeAdbStartOptions,
        output: PrivilegeAdbOutput,
        onManagedWirelessDebugging: (PrivilegeAdbWirelessDebuggingController) -> Unit,
    ): PrivilegeAdbEndpoint {
        val controller = wirelessDebuggingControllerProvider()
        val status = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            unsupportedWirelessDebuggingControlStatus()
        } else {
            controller.status()
        }
        output.append(
            "diag",
            "Wireless debugging control supported=${status.supported}, " +
                "declared=${status.permissionDeclared}, permission=${status.permissionGranted}, " +
                "enabled=${status.wirelessDebuggingEnabled}",
        )
        val managedWirelessDebuggingEnabled =
            shouldEnableWirelessDebuggingForStart(options.wirelessDebuggingControl, status)
        if (managedWirelessDebuggingEnabled) {
            output.append("adb", "Temporarily enabling Wireless debugging")
            controller.prepareAdb()
            controller.setWirelessDebuggingEnabled(true)
            onManagedWirelessDebugging(controller)
        } else if (status.wirelessDebuggingEnabled) {
            output.append("diag", "Wireless debugging already enabled")
        } else if (shouldRejectWirelessDebuggingForStart(options.wirelessDebuggingControl, status)) {
            throw PrivilegeAdbException(
                status.failureMessage ?: if (!status.permissionDeclared) {
                    "WRITE_SECURE_SETTINGS must be declared to manage Wireless debugging"
                } else {
                    "WRITE_SECURE_SETTINGS is required to manage Wireless debugging"
                },
            )
        }
        return discoverConnectEndpointForStart(
            options = options,
            output = output,
            managedWirelessDebuggingEnabled = managedWirelessDebuggingEnabled,
        )
    }

    private suspend fun discoverConnectEndpointForStart(
        options: PrivilegeAdbStartOptions,
        output: PrivilegeAdbOutput,
        managedWirelessDebuggingEnabled: Boolean,
    ): PrivilegeAdbEndpoint {
        val attempts = managedWirelessConnectPortDiscoveryAttempts(
            managedWirelessDebuggingEnabled = managedWirelessDebuggingEnabled,
            connectRetryCount = options.connectRetryCount,
        )
        var lastFailure: Throwable? = null
        repeat(attempts) { attemptIndex ->
            if (attemptIndex > 0) {
                output.append("diag", "Retrying ADB connect port discovery after enabling Wireless debugging")
            }
            runCatching {
                discoverConnectEndpoint(options.portDiscoveryTimeoutMillis)
            }.onSuccess { endpoint ->
                return endpoint
            }.onFailure { throwable ->
                throwable.rethrowIfInterrupted()
                lastFailure = throwable
                if (attemptIndex < attempts - 1) {
                    output.append("diag", "ADB connect port discovery failed: ${throwable.toFailureMessage()}")
                }
            }
        }
        throw lastFailure ?: PrivilegeStartupException("Failed to discover ADB connect port")
    }

    @Throws(PrivilegeStartupException::class)
    public suspend fun switchToTcp(
        currentPort: Int? = null,
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
        options: PrivilegeAdbStartOptions? = null,
    ): PrivilegeAdbTcpResult {
        require(currentPort == null || currentPort.isPrivilegeAdbPort()) {
            "currentPort must be between 1 and 65535"
        }
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        var managedWirelessDebuggingController: PrivilegeAdbWirelessDebuggingController? = null
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

            val connectEndpoint = currentPort?.let(PrivilegeAdbEndpoint::local)
                ?: activeTcpPort?.takeIf { it > 0 }?.let(PrivilegeAdbEndpoint::local)
                ?: options?.port?.let(PrivilegeAdbEndpoint::local)
                ?: if (options == null) {
                    discoverConnectEndpoint()
                } else if (options.discoverPort) {
                    discoverConnectEndpointForStart(options, output) { controller ->
                        managedWirelessDebuggingController = controller
                    }
                } else {
                    throw PrivilegeAdbException("ADB connect port is not available")
                }
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(connectEndpoint, key).cancellableUse { client ->
                try {
                    client.connect(output)
                } catch (throwable: Throwable) {
                    throwable.rethrowIfInterrupted()
                    throw throwable.toLocalNetworkAccessFailure(connectEndpoint)
                }
                runCatching {
                    client.command("tcpip:$tcpPort", output)
                }.onFailure { throwable ->
                    throwable.rethrowIfInterrupted()
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
            throwable.rethrowIfInterrupted()
            throw PrivilegeStartupException("Failed to switch ADB to TCP mode: ${output.text()}", throwable)
        } finally {
            disableManagedWirelessDebuggingAfterStart(
                shouldDisable = options?.disableWirelessDebuggingAfterStart == true,
                controller = managedWirelessDebuggingController,
                output = output,
            )
        }
    }

    @Throws(PrivilegeStartupException::class)
    public suspend fun stopTcp(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpResult {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        return try {
            val key = createKey()
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(tcpPort, key).cancellableUse { client ->
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
            throwable.rethrowIfInterrupted()
            throw PrivilegeStartupException("Failed to stop ADB TCP mode: ${output.text()}", throwable)
        }
    }

    public suspend fun checkTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbAuthorizationCheckResult {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        return try {
            openTcpAuthorizationCheckSession(tcpPort).use { session ->
                session.check()
            }
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            tcpAuthorizationKeyFailureResult(throwable)
        }
    }

    public fun openTcpAuthorizationCheckSession(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    ): PrivilegeAdbTcpAuthorizationCheckSession {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val key = try {
            createKey()
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
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

    public suspend fun requestTcpAuthorization(
        tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
        timeoutMillis: Long = PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS,
    ): PrivilegeAdbAuthorizationRequestResult = withContext(Dispatchers.IO) {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(timeoutMillis <= Int.MAX_VALUE) { "timeoutMillis must be at most ${Int.MAX_VALUE}" }

        val output = PrivilegeAdbOutput()
        val key = runCatching { createKey() }.getOrElse { throwable ->
            throwable.rethrowIfInterrupted()
            val failureMessage = throwable.toFailureMessage()
            output.append("diag", "ADB TCP authorization request failed: $failureMessage")
            return@withContext PrivilegeAdbAuthorizationRequestResult(
                authorized = false,
                endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
                outputText = output.text(),
                failureMessage = failureMessage,
            )
        }
        try {
            output.append("diag", "ADB identity name=${identity.adbDeviceName}, keySignature=<redacted>")
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            val client = PrivilegeAdbClient(
                port = tcpPort,
                key = key,
                socketReadTimeoutMillis = timeoutMillis.toInt(),
            )
            client.cancellableUse {
                if (client.requestAuthorization(output) == PrivilegeAdbAuthorizationStatus.AUTHORIZED) {
                    PrivilegeAdbAuthorizationRequestResult(
                        authorized = true,
                        outputText = output.text(),
                    )
                } else {
                    PrivilegeAdbAuthorizationRequestResult(
                        authorized = false,
                        endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
                        outputText = output.text(),
                        failureMessage = "ADB key authorization did not complete",
                    )
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throwable.rethrowIfInterrupted()
            val timedOut = throwable is SocketTimeoutException
            PrivilegeAdbAuthorizationRequestResult(
                authorized = false,
                endReason = if (timedOut) {
                    PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT
                } else {
                    PrivilegeAdbAuthorizationEndReason.FAILED
                },
                outputText = output.text(),
                failureMessage = if (timedOut) null else throwable.toFailureMessage(),
            )
        }
    }

    private fun createKey(): PrivilegeAdbKey =
        try {
            PrivilegeAdbKey(
                keyBytes = loadKeyBytes(),
                name = identity.adbDeviceName,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            throw PrivilegeAdbException("Failed to load ADB key", throwable)
        }

    private suspend fun runDiagnosticShellCommand(
        endpoint: PrivilegeAdbEndpoint,
        key: PrivilegeAdbKey,
        label: String,
        command: String,
        output: PrivilegeAdbOutput,
    ) {
        runCatching {
            PrivilegeAdbClient(endpoint, key).cancellableUse { client ->
                client.connect(output)
                client.command("shell:$command", output)
            }
        }.onFailure { throwable ->
            throwable.rethrowIfInterrupted()
            output.append(
                "diag",
                "Failed to read $label: ${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
    }

    private suspend fun appendRuntimeDiagnostics(
        endpoint: PrivilegeAdbEndpoint,
        key: PrivilegeAdbKey,
        output: PrivilegeAdbOutput,
    ) {
        output.append("diag", "Reading server process state")
        runDiagnosticShellCommand(
            endpoint = endpoint,
            key = key,
            label = "server process state",
            command = "ps -A | grep '[p]riv-kit-server' 2>&1 || true",
            output = output,
        )
        output.append("diag", "Reading PrivKit logcat")
        runDiagnosticShellCommand(
            endpoint = endpoint,
            key = key,
            label = "PrivKit logcat",
            command = "logcat -d -t 160 -s PrivKitServer:D PrivKit:D AndroidRuntime:E '*:S' 2>&1",
            output = output,
        )
    }

    private fun connectWithRetry(
        client: PrivilegeAdbClient,
        endpoint: PrivilegeAdbEndpoint,
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
                throwable.rethrowIfInterrupted()
                output.append("diag", "ADB connect attempt $attempt failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                if (!shouldRetryAdbConnectFailure(throwable, attempt, options.connectRetryCount)) {
                    throw throwable.toLocalNetworkAccessFailure(endpoint)
                }
                nextDelay = options.connectRetryDelayMillis
            }
        }
    }

    internal companion object {
        internal fun create(
            adbDeviceName: String = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbStarter =
            PrivilegeContext.require().let { applicationContext ->
                PrivilegeAdbStarter(
                    identity = PrivilegeAdbIdentity.default(deviceName = adbDeviceName),
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

internal fun shouldEnableWirelessDebuggingForStart(
    control: PrivilegeAdbWirelessDebuggingControl,
    status: PrivilegeAdbWirelessDebuggingControlStatus,
): Boolean =
    control != PrivilegeAdbWirelessDebuggingControl.NEVER &&
        !status.wirelessDebuggingEnabled &&
        status.canManage

private fun unsupportedWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
    PrivilegeAdbWirelessDebuggingControlStatus(
        supported = false,
        permissionDeclared = false,
        permissionGranted = false,
        wirelessDebuggingEnabled = false,
        canManage = false,
        failureMessage = "Wireless ADB requires Android 11 or above",
    )

private fun disableManagedWirelessDebuggingAfterStart(
    shouldDisable: Boolean,
    controller: PrivilegeAdbWirelessDebuggingController?,
    output: PrivilegeAdbOutput,
) {
    if (!shouldDisable || controller == null) return
    runCatching {
        controller.setWirelessDebuggingEnabled(false)
        output.append("adb", "Wireless debugging disabled")
    }.onFailure { throwable ->
        output.append("diag", "Failed to disable Wireless debugging: ${throwable.toFailureMessage()}")
    }
}

internal fun shouldRejectWirelessDebuggingForStart(
    control: PrivilegeAdbWirelessDebuggingControl,
    status: PrivilegeAdbWirelessDebuggingControlStatus,
): Boolean =
    control == PrivilegeAdbWirelessDebuggingControl.REQUIRE &&
        !status.wirelessDebuggingEnabled &&
        !status.canManage

internal fun managedWirelessConnectPortDiscoveryAttempts(
    managedWirelessDebuggingEnabled: Boolean,
    connectRetryCount: Int,
): Int =
    if (managedWirelessDebuggingEnabled) {
        minOf(connectRetryCount, PRIVILEGE_ADB_MANAGED_WIRELESS_CONNECT_PORT_DISCOVERY_ATTEMPTS)
    } else {
        1
    }

internal fun shouldRetryAdbConnectFailure(
    throwable: Throwable,
    attempt: Int,
    retryCount: Int,
): Boolean =
    attempt < retryCount &&
        throwable !is InterruptedException &&
        throwable !is SocketTimeoutException &&
        !throwable.isAdbKeyNotAuthorized()

internal suspend fun recoverTcpAuthorizationForStart(
    initialResult: PrivilegeAdbAuthorizationCheckResult,
    requestedTcpPort: Int,
    configuredTcpPort: Int?,
    canManageAdb: Boolean,
    retryCount: Int = PRIVILEGE_ADB_TCP_PREPARATION_RETRY_COUNT,
    retryDelayMillis: Long = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
    enableAdb: () -> Unit,
    checkAuthorization: suspend () -> PrivilegeAdbAuthorizationCheckResult,
    sleep: suspend (Long) -> Unit,
): PrivilegeAdbAuthorizationCheckResult {
    if (
        initialResult.status != PrivilegeAdbAuthorizationStatus.UNAVAILABLE ||
        configuredTcpPort != requestedTcpPort ||
        !canManageAdb
    ) {
        return initialResult
    }
    require(retryCount > 0) { "retryCount must be positive" }
    require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }

    return try {
        enableAdb()
        var result = initialResult
        repeat(retryCount) {
            if (retryDelayMillis > 0L) sleep(retryDelayMillis)
            result = checkAuthorization()
            if (result.status != PrivilegeAdbAuthorizationStatus.UNAVAILABLE) return result
        }
        result
    } catch (throwable: Throwable) {
        throwable.rethrowIfInterrupted()
        initialResult.withTcpPreparationFailure(throwable)
    }
}

private fun PrivilegeAdbAuthorizationCheckResult.withTcpPreparationFailure(
    throwable: Throwable,
): PrivilegeAdbAuthorizationCheckResult {
    val message = "Failed to prepare ADB for static TCP: ${throwable.toFailureMessage()}"
    val previousOutput = outputText.takeUnless { it == "<no output>" }.orEmpty()
    return copy(
        outputText = listOf(previousOutput, "[diag] $message")
            .filter { it.isNotBlank() }
            .joinToString("\n"),
        failureMessage = message,
    )
}

private const val PRIVILEGE_ADB_MANAGED_WIRELESS_CONNECT_PORT_DISCOVERY_ATTEMPTS = 3
private const val PRIVILEGE_ADB_TCP_PREPARATION_RETRY_COUNT = 5

internal fun Throwable.rethrowIfInterrupted() {
    if (this is CancellationException) throw this
    if (this is InterruptedException) {
        Thread.currentThread().interrupt()
        throw this
    }
}

private fun Throwable.toLocalNetworkAccessFailure(endpoint: PrivilegeAdbEndpoint): Throwable =
    if (!endpoint.isLocalHost && !isAdbKeyNotAuthorized()) {
        PrivilegeAdbLocalNetworkAccessException(endpoint, this)
    } else {
        this
    }

private suspend fun <T : AutoCloseable, R> T.cancellableUse(block: (T) -> R): R =
    cancellableAdbCall(cancel = ::close) { use(block) }
