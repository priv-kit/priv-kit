package priv.kit.core.adb

import priv.kit.core.PrivilegeStartupException
import priv.kit.shared.isPrivilegeAdbPort

internal class PrivilegeAdbPairingManager(
    private val identityProvider: PrivilegeAdbIdentityProvider,
    private val endpointResolver: PrivilegeAdbEndpointResolver,
) {
    @Throws(PrivilegeStartupException::class)
    suspend fun checkPairing(
        port: Int?,
        discoverPort: Boolean,
        portDiscoveryTimeoutMillis: Long,
    ): PrivilegeAdbPairingCheckResult =
        openPairingCheckSession(
            port = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
        ).use { session ->
            session.check()
        }

    @Throws(PrivilegeStartupException::class)
    fun openPairingCheckSession(
        port: Int?,
        discoverPort: Boolean,
        portDiscoveryTimeoutMillis: Long,
    ): PrivilegeAdbPairingCheckSession {
        require(port == null || port.isPrivilegeAdbPort()) { "port must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        val key = try {
            identityProvider.loadKey()
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to check wireless ADB pairing", throwable)
        }
        return PrivilegeAdbPairingCheckSession(
            identity = identityProvider.identity,
            publicKeyFingerprint = key.adbPublicKeyFingerprint,
            explicitPort = port,
            discoverPort = discoverPort,
            portDiscoveryTimeoutMillis = portDiscoveryTimeoutMillis,
            discoverConnectEndpoint = endpointResolver::discoverConnectEndpoint,
            clientFactory = { activeEndpoint -> PrivilegeAdbClient(activeEndpoint, key) },
        )
    }

    @Throws(PrivilegeStartupException::class)
    suspend fun pair(
        pairingCode: String,
        port: Int?,
        discoverPort: Boolean,
        portDiscoveryTimeoutMillis: Long,
    ): PrivilegeAdbPairingResult {
        val normalizedPairingCode = pairingCode.toPrivilegeAdbPairingCode()
        require(port == null || port.isPrivilegeAdbPort()) { "port must be between 1 and 65535" }
        require(normalizedPairingCode.isNotBlank()) { "pairingCode must contain six ASCII digits" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }

        return try {
            val key = identityProvider.loadKey()
            val activeEndpoint = port?.let(PrivilegeAdbEndpoint::local) ?: if (discoverPort) {
                endpointResolver.discoverPairingEndpoint(portDiscoveryTimeoutMillis)
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
                identity = identityProvider.identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to pair with wireless ADB", throwable)
        }
    }

    @Throws(PrivilegeStartupException::class)
    suspend fun discoverPairingPort(
        timeoutMillis: Long,
    ): Int =
        endpointResolver.discoverPairingEndpoint(timeoutMillis).port
}
