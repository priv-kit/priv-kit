package priv.kit.core.adb

import android.net.nsd.NsdManager
import android.os.Build
import priv.kit.core.PrivilegeStartupException
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeAdbEndpointResolver(
    private val nsdManagerProvider: () -> NsdManager,
    private val wirelessDebuggingControllerProvider: () -> PrivilegeAdbWirelessDebuggingController,
) {
    fun getWirelessDebuggingControlStatus(): PrivilegeAdbWirelessDebuggingControlStatus =
        wirelessDebuggingControllerProvider().status()

    suspend fun discoverPairingEndpoint(
        timeoutMillis: Long,
    ): PrivilegeAdbEndpoint {
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_PAIRING)
                .discoverEndpoint(timeoutMillis)
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB pairing port", throwable)
        }
    }

    suspend fun discoverConnectEndpoint(
        timeoutMillis: Long,
    ): PrivilegeAdbEndpoint {
        return try {
            PrivilegeAdbMdns(nsdManagerProvider(), PrivilegeAdbMdns.TLS_CONNECT)
                .discoverEndpoint(timeoutMillis)
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to discover ADB connect port", throwable)
        }
    }

    suspend fun acquireConnectEndpointForStart(
        options: PrivilegeAdbStartOptions,
        output: PrivilegeAdbOutput,
        disableWirelessDebuggingAfterUse: Boolean,
    ): PrivilegeAdbConnectEndpointLease {
        requireWirelessAdbSupported()
        val controller = wirelessDebuggingControllerProvider()
        val status = controller.status()
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
        val cleanupController = controller.takeIf {
            managedWirelessDebuggingEnabled && disableWirelessDebuggingAfterUse
        }
        return try {
            PrivilegeAdbConnectEndpointLease(
                endpoint = discoverConnectEndpointForStart(
                    options = options,
                    output = output,
                    managedWirelessDebuggingEnabled = managedWirelessDebuggingEnabled,
                ),
                cleanupController = cleanupController,
                output = output,
            )
        } catch (throwable: Throwable) {
            disableManagedWirelessDebuggingAfterStart(
                shouldDisable = cleanupController != null,
                controller = cleanupController,
                output = output,
            )
            throw throwable
        }
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
}

internal class PrivilegeAdbConnectEndpointLease(
    val endpoint: PrivilegeAdbEndpoint,
    private val cleanupController: PrivilegeAdbWirelessDebuggingController?,
    private val output: PrivilegeAdbOutput,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        check(closed.compareAndSet(false, true)) {
            "ADB connect endpoint lease is already closed"
        }
        disableManagedWirelessDebuggingAfterStart(
            shouldDisable = cleanupController != null,
            controller = cleanupController,
            output = output,
        )
    }
}

internal fun managedWirelessConnectPortDiscoveryAttempts(
    managedWirelessDebuggingEnabled: Boolean,
    connectRetryCount: Int,
): Int =
    if (managedWirelessDebuggingEnabled) {
        minOf(connectRetryCount, PRIVILEGE_ADB_MANAGED_WIRELESS_CONNECT_PORT_DISCOVERY_ATTEMPTS)
    } else {
        1
    }

private const val PRIVILEGE_ADB_MANAGED_WIRELESS_CONNECT_PORT_DISCOVERY_ATTEMPTS = 3

internal fun requireWirelessAdbSupported() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        throw PrivilegeStartupException("Wireless ADB requires Android 11 or above")
    }
}
