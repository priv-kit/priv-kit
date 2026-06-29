package priv.kit.adb

public class PrivilegeAdbIdentity private constructor(
    public val deviceName: String,
) {
    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(deviceName.length <= MAX_DEVICE_NAME_LENGTH) {
            "deviceName must be at most $MAX_DEVICE_NAME_LENGTH characters"
        }
        require(deviceName.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "deviceName must not contain control line characters"
        }
    }

    // Android parses the ADB public key comment with whitespace splitting, so
    // names written to the key comment must be a single token.
    internal val adbDeviceName: String
        get() = deviceName.filterNot { it.isWhitespace() }

    public companion object {
        public const val DEFAULT_DEVICE_NAME: String = "priv-kit"
        public const val MAX_DEVICE_NAME_LENGTH: Int = 128
        private const val MAX_OWNER_TOKEN_LENGTH = 128

        internal fun default(deviceName: String = DEFAULT_DEVICE_NAME): PrivilegeAdbIdentity =
            PrivilegeAdbIdentity(deviceName = deviceName)

        internal fun forOwnerToken(
            ownerToken: String,
            deviceName: String = DEFAULT_DEVICE_NAME,
        ): PrivilegeAdbIdentity {
            validateOwnerToken(ownerToken)
            return PrivilegeAdbIdentity(deviceName = deviceName)
        }

        private fun validateOwnerToken(ownerToken: String) {
            val normalizedOwnerToken = ownerToken.trim()
            require(normalizedOwnerToken.isNotBlank()) { "ownerToken must not be blank" }
            require(normalizedOwnerToken.length <= MAX_OWNER_TOKEN_LENGTH) {
                "ownerToken must be at most $MAX_OWNER_TOKEN_LENGTH characters"
            }
            require(normalizedOwnerToken.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                "ownerToken must not contain control line characters"
            }
        }
    }
}

public data class PrivilegeAdbIdentityInfo public constructor(
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String,
)

public data class PrivilegeAdbStartOptions public constructor(
    public val host: String = DEFAULT_ADB_HOST,
    public val port: Int? = null,
    public val discoverPort: Boolean = true,
    public val tcpMode: Boolean = false,
    public val tcpPort: Int = DEFAULT_ADB_TCP_PORT,
    public val portDiscoveryTimeoutMillis: Long = DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS,
    public val connectRetryCount: Int = DEFAULT_ADB_CONNECT_RETRY_COUNT,
    public val connectRetryDelayMillis: Long = DEFAULT_ADB_CONNECT_RETRY_DELAY_MILLIS,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }
        require(connectRetryCount > 0) { "connectRetryCount must be positive" }
        require(connectRetryDelayMillis >= 0L) { "connectRetryDelayMillis must not be negative" }
    }
}

private const val DEFAULT_ADB_HOST: String = "127.0.0.1"
private const val DEFAULT_ADB_TCP_PORT: Int = 5555
private const val DEFAULT_ADB_CONNECT_RETRY_COUNT: Int = 5
private const val DEFAULT_ADB_CONNECT_RETRY_DELAY_MILLIS: Long = 1_000L
private const val DEFAULT_ADB_PORT_DISCOVERY_TIMEOUT_MILLIS: Long = 15_000L

public data class PrivilegeAdbStartResult public constructor(
    public val host: String,
    public val port: Int,
    public val outputText: String,
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
    public val outputText: String,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
    public val failureMessage: String? = null,
)

public data class PrivilegeAdbTcpResult public constructor(
    public val host: String,
    public val port: Int,
    public val outputText: String,
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
