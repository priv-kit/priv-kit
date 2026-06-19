package priv.kit.adb

import priv.kit.core.PrivilegeServerLaunchCommand

data class PrivilegeAdbCommand(
    val commandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val launchCommand: PrivilegeServerLaunchCommand,
    val diagnosticLogPath: String? = null,
)

class PrivilegeAdbIdentity private constructor(
    val deviceName: String,
    internal val storageSignature: String,
) {
    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(storageSignature.isNotBlank()) { "storageSignature must not be blank" }
        require(deviceName.length <= MAX_DEVICE_NAME_LENGTH) {
            "deviceName must be at most $MAX_DEVICE_NAME_LENGTH characters"
        }
        require(storageSignature.length <= MAX_STORAGE_SIGNATURE_LENGTH) {
            "storageSignature must be at most $MAX_STORAGE_SIGNATURE_LENGTH characters"
        }
        require(deviceName.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "deviceName must not contain control line characters"
        }
        require(storageSignature.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "storageSignature must not contain control line characters"
        }
    }

    // Android parses the ADB public key comment with whitespace splitting, so
    // names written to the key comment must be a single token.
    internal val adbDeviceName: String
        get() = deviceName.filterNot { it.isWhitespace() }

    companion object {
        const val DEFAULT_DEVICE_NAME = "priv-kit"
        const val MAX_DEVICE_NAME_LENGTH = 128
        internal const val DEFAULT_STORAGE_SIGNATURE = "default"
        private const val MAX_STORAGE_SIGNATURE_LENGTH = 128

        internal fun default(deviceName: String = DEFAULT_DEVICE_NAME): PrivilegeAdbIdentity =
            PrivilegeAdbIdentity(
                deviceName = deviceName,
                storageSignature = DEFAULT_STORAGE_SIGNATURE,
            )

        internal fun forOwnerToken(
            ownerToken: String,
            deviceName: String = DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbIdentity =
            PrivilegeAdbIdentity(
                deviceName = deviceName,
                storageSignature = ownerToken.trim(),
            )
    }
}

data class PrivilegeAdbIdentityInfo(
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String,
)

data class PrivilegeAdbStartOptions(
    val host: String = DEFAULT_HOST,
    val port: Int? = null,
    val discoverPort: Boolean = true,
    val tcpMode: Boolean = false,
    val tcpPort: Int = DEFAULT_TCP_PORT,
    val portDiscoveryTimeoutMillis: Long = DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    val connectRetryCount: Int = DEFAULT_CONNECT_RETRY_COUNT,
    val connectRetryDelayMillis: Long = DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }
        require(connectRetryCount > 0) { "connectRetryCount must be positive" }
        require(connectRetryDelayMillis >= 0L) { "connectRetryDelayMillis must not be negative" }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_TCP_PORT = 5555
        const val DEFAULT_CONNECT_RETRY_COUNT = 5
        const val DEFAULT_CONNECT_RETRY_DELAY_MILLIS = 1_000L
        const val DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS = 15_000L
    }
}

data class PrivilegeAdbStartResult(
    val command: PrivilegeAdbCommand,
    val host: String,
    val port: Int,
    val output: PrivilegeAdbOutput,
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String = "",
)

data class PrivilegeAdbPairingResult(
    val host: String,
    val port: Int,
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String = "",
)

data class PrivilegeAdbPairingCheckResult(
    val host: String,
    val port: Int?,
    val paired: Boolean,
    val output: PrivilegeAdbOutput,
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String = "",
    val failureMessage: String? = null,
)

data class PrivilegeAdbTcpResult(
    val host: String,
    val port: Int,
    val output: PrivilegeAdbOutput,
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String = "",
)

internal object PrivilegeAdbPortSelector {
    fun chooseStartPort(
        explicitPort: Int?,
        activeTcpPort: Int,
        tcpMode: Boolean,
        discoveredPort: Int?,
    ): Int {
        explicitPort?.let { return it }
        if (tcpMode && activeTcpPort > 0) return activeTcpPort
        return discoveredPort ?: throw PrivilegeAdbException("ADB port is not available")
    }
}
