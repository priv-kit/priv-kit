package priv.kit.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import priv.kit.core.PrivilegeStartupException
import priv.kit.shared.isPrivilegeAdbPort
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeAdbTcpManager(
    private val identityProvider: PrivilegeAdbIdentityProvider,
    private val endpointResolver: PrivilegeAdbEndpointResolver,
    private val wirelessDebuggingControllerProvider: () -> PrivilegeAdbWirelessDebuggingController,
) {
    fun getActiveTcpPort(): Int? =
        PrivilegeAdbEnvironment.getActiveAdbTcpPort().takeIf { it > 0 }

    fun getConfiguredTcpPort(): Int? =
        PrivilegeAdbEnvironment.getConfiguredAdbTcpPort().takeIf { it > 0 }

    suspend fun prepareTcpForStart(
        tcpPort: Int,
    ): PrivilegeAdbAuthorizationCheckResult {
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
            retryCount = PRIVILEGE_ADB_TCP_PREPARATION_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )
    }

    fun getWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
        endpointResolver.getWirelessDebuggingControlStatus()

    @Throws(PrivilegeStartupException::class)
    suspend fun discoverConnectPort(
        timeoutMillis: Long,
    ): Int =
        endpointResolver.discoverConnectEndpoint(timeoutMillis).port

    @Throws(PrivilegeStartupException::class)
    suspend fun switchToTcp(
        currentPort: Int?,
        tcpPort: Int,
        options: PrivilegeAdbStartOptions?,
    ): PrivilegeAdbTcpResult {
        require(currentPort == null || currentPort.isPrivilegeAdbPort()) {
            "currentPort must be between 1 and 65535"
        }
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        var connectEndpointLease: PrivilegeAdbConnectEndpointLease? = null
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
                    identity = identityProvider.identity,
                )
            }

            val connectEndpoint = currentPort?.let(PrivilegeAdbEndpoint::local)
                ?: activeTcpPort?.takeIf { it > 0 }?.let(PrivilegeAdbEndpoint::local)
                ?: options?.port?.let(PrivilegeAdbEndpoint::local)
                ?: if (options == null) {
                    requireWirelessAdbSupported()
                    endpointResolver.discoverConnectEndpoint(
                        timeoutMillis = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
                    )
                } else if (options.discoverPort) {
                    endpointResolver.acquireConnectEndpointForStart(
                        options = options,
                        output = output,
                        disableWirelessDebuggingAfterUse =
                            options.disableWirelessDebuggingAfterStart,
                    ).also { lease ->
                        connectEndpointLease = lease
                    }.endpoint
                } else {
                    throw PrivilegeAdbException("ADB connect port is not available")
                }
            val key = identityProvider.loadKey()
            output.append(
                "diag",
                "ADB identity name=${identityProvider.identity.adbDeviceName}, keySignature=<redacted>",
            )
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
                identity = identityProvider.identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            throw PrivilegeStartupException("Failed to switch ADB to TCP mode: ${output.text()}", throwable)
        } finally {
            connectEndpointLease?.close()
        }
    }

    @Throws(PrivilegeStartupException::class)
    suspend fun stopTcp(
        tcpPort: Int,
    ): PrivilegeAdbTcpResult {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val output = PrivilegeAdbOutput()
        return try {
            val key = identityProvider.loadKey()
            output.append(
                "diag",
                "ADB identity name=${identityProvider.identity.adbDeviceName}, keySignature=<redacted>",
            )
            output.append("diag", "ADB public key fingerprint=${key.adbPublicKeyFingerprint}")
            PrivilegeAdbClient(tcpPort, key).cancellableUse { client ->
                client.connect(output)
                client.command("usb:", output)
            }
            PrivilegeAdbTcpResult(
                port = tcpPort,
                outputText = output.text(),
                identity = identityProvider.identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            throw PrivilegeStartupException("Failed to stop ADB TCP mode: ${output.text()}", throwable)
        }
    }

    suspend fun checkTcpAuthorization(
        tcpPort: Int,
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

    @Throws(PrivilegeStartupException::class)
    fun openTcpAuthorizationCheckSession(
        tcpPort: Int,
    ): PrivilegeAdbTcpAuthorizationCheckSession {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        val key = try {
            identityProvider.loadKey()
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException(
                "Failed to open ADB TCP authorization check session",
                throwable,
            )
        }
        return PrivilegeAdbTcpAuthorizationCheckSession(
            identity = identityProvider.identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
            tcpPort = tcpPort,
            clientFactory = { PrivilegeAdbClient(tcpPort, key) },
        )
    }

    suspend fun requestTcpAuthorization(
        tcpPort: Int,
        timeoutMillis: Long,
    ): PrivilegeAdbAuthorizationRequestResult = withContext(Dispatchers.IO) {
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(timeoutMillis <= Int.MAX_VALUE) { "timeoutMillis must be at most ${Int.MAX_VALUE}" }

        val output = PrivilegeAdbOutput()
        val key = runCatching { identityProvider.loadKey() }.getOrElse { throwable ->
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
            output.append(
                "diag",
                "ADB identity name=${identityProvider.identity.adbDeviceName}, keySignature=<redacted>",
            )
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

    private fun tcpAuthorizationKeyFailureResult(
        throwable: Throwable,
    ): PrivilegeAdbAuthorizationCheckResult {
        val output = PrivilegeAdbOutput()
        val failureMessage = throwable.toFailureMessage()
        output.append("diag", "ADB TCP authorization check failed: $failureMessage")
        return throwable.toTcpAuthorizationCheckResult(
            output = output,
            identity = identityProvider.identity,
            publicKeyFingerprint = "",
        )
    }
}

internal suspend fun recoverTcpAuthorizationForStart(
    initialResult: PrivilegeAdbAuthorizationCheckResult,
    requestedTcpPort: Int,
    configuredTcpPort: Int?,
    canManageAdb: Boolean,
    retryCount: Int,
    retryDelayMillis: Long,
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

private const val PRIVILEGE_ADB_TCP_PREPARATION_RETRY_COUNT = 5
