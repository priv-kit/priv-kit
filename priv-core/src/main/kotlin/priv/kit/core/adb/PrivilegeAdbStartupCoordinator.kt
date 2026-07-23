package priv.kit.core.adb

import priv.kit.core.PrivilegeServerLaunchUncertainException
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.internal.core.PrivilegeServerLaunchCommand
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PrivilegeAdbStartupCoordinator(
    private val identityProvider: PrivilegeAdbIdentityProvider,
    private val endpointResolver: PrivilegeAdbEndpointResolver,
) {
    @Throws(PrivilegeStartupException::class)
    suspend fun start(
        command: PrivilegeServerLaunchCommand,
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeAdbStartResult = withContext(Dispatchers.IO) {
        val output = PrivilegeAdbOutput(startupLogListener)
        var connectEndpointLease: PrivilegeAdbConnectEndpointLease? = null
        try {
            val key = identityProvider.loadKey()
            output.append(
                "diag",
                "ADB identity name=${identityProvider.identity.adbDeviceName}, keySignature=<redacted>",
            )
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
                    endpointResolver.acquireConnectEndpointForStart(
                        options = options,
                        output = output,
                        disableWirelessDebuggingAfterUse = options.disableWirelessDebuggingAfterStart,
                    ).also { lease ->
                        connectEndpointLease = lease
                    }.endpoint
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
                identity = identityProvider.identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to start Privileged Server with ADB: ${output.text()}", throwable)
        } finally {
            connectEndpointLease?.close()
        }
    }

    suspend fun readRuntimeDiagnostics(
        endpoint: PrivilegeAdbEndpoint,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        val output = PrivilegeAdbOutput(startupLogListener)
        appendRuntimeDiagnostics(
            endpoint = endpoint,
            key = identityProvider.loadKey(),
            output = output,
        )
        return output.text()
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
                output.append(
                    "diag",
                    "ADB connect attempt $attempt failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
                )
                if (!shouldRetryAdbConnectFailure(throwable, attempt, options.connectRetryCount)) {
                    throw throwable.toLocalNetworkAccessFailure(endpoint)
                }
                nextDelay = options.connectRetryDelayMillis
            }
        }
    }
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
