package priv.kit.adb

public data class PrivilegeAdbCommand public constructor(
    public val commandLine: String,
    public val classpath: String,
    public val mainClass: String,
    public val providerAuthority: String,
    public val diagnosticLogPath: String? = null,
)

public class PrivilegeAdbIdentity private constructor(
    public val deviceName: String,
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

    public companion object {
        public const val DEFAULT_DEVICE_NAME: String = "priv-kit"
        public const val MAX_DEVICE_NAME_LENGTH: Int = 128
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

public data class PrivilegeAdbIdentityInfo public constructor(
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String,
)

public data class PrivilegeAdbStartOptions public constructor(
    public val host: String = DEFAULT_HOST,
    public val port: Int? = null,
    public val discoverPort: Boolean = true,
    public val tcpMode: Boolean = false,
    public val tcpPort: Int = DEFAULT_TCP_PORT,
    public val portDiscoveryTimeoutMillis: Long = DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    public val connectRetryCount: Int = DEFAULT_CONNECT_RETRY_COUNT,
    public val connectRetryDelayMillis: Long = DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }
        require(connectRetryCount > 0) { "connectRetryCount must be positive" }
        require(connectRetryDelayMillis >= 0L) { "connectRetryDelayMillis must not be negative" }
    }

    public companion object {
        public const val DEFAULT_HOST: String = "127.0.0.1"
        public const val DEFAULT_TCP_PORT: Int = 5555
        public const val DEFAULT_CONNECT_RETRY_COUNT: Int = 5
        public const val DEFAULT_CONNECT_RETRY_DELAY_MILLIS: Long = 1_000L
        public const val DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS: Long = 15_000L
    }
}

public data class PrivilegeAdbStartResult public constructor(
    public val command: PrivilegeAdbCommand,
    public val host: String,
    public val port: Int,
    public val output: PrivilegeAdbOutput,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
)

public data class PrivilegeAdbPairingResult public constructor(
    public val host: String,
    public val port: Int,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
)

public data class PrivilegeAdbPairingCheckResult public constructor(
    public val host: String,
    public val port: Int?,
    public val paired: Boolean,
    public val output: PrivilegeAdbOutput,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
    public val failureMessage: String? = null,
)

public data class PrivilegeAdbTcpResult public constructor(
    public val host: String,
    public val port: Int,
    public val output: PrivilegeAdbOutput,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
)

internal object PrivilegeAdbPortSelector {
    fun chooseStartPort(
        explicitPort: Int?,
        activeTcpPort: Int,
        tcpMode: Boolean,
        targetTcpPort: Int,
        discoveredPort: Int?,
    ): Int {
        explicitPort?.let { return it }
        if (tcpMode && activeTcpPort > 0) return activeTcpPort
        discoveredPort?.let { return it }
        if (tcpMode) return targetTcpPort
        throw PrivilegeAdbException("ADB port is not available")
    }
}
