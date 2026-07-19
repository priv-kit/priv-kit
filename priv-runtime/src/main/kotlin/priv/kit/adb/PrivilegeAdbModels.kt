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

    internal companion object {
        internal const val DEFAULT_DEVICE_NAME: String = "priv-kit"
        internal const val MAX_DEVICE_NAME_LENGTH: Int = 128

        internal fun default(deviceName: String = DEFAULT_DEVICE_NAME): PrivilegeAdbIdentity =
            PrivilegeAdbIdentity(deviceName = deviceName)
    }
}

public data class PrivilegeAdbIdentityInfo public constructor(
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String,
)

public data class PrivilegeAdbStartOptions public constructor(
    public val port: Int? = null,
    public val discoverPort: Boolean = true,
    public val tcpMode: Boolean = false,
    public val tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    public val wirelessDebuggingControl: PrivilegeAdbWirelessDebuggingControl =
        PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
    public val disableWirelessDebuggingAfterStart: Boolean = true,
    public val portDiscoveryTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS,
    public val connectRetryCount: Int = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_COUNT,
    public val connectRetryDelayMillis: Long = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
) {
    init {
        require(port == null || port in 1..65535) { "port must be between 1 and 65535" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(portDiscoveryTimeoutMillis > 0L) { "portDiscoveryTimeoutMillis must be positive" }
        require(connectRetryCount > 0) { "connectRetryCount must be positive" }
        require(connectRetryDelayMillis >= 0L) { "connectRetryDelayMillis must not be negative" }
    }
}

internal const val PRIVILEGE_ADB_LOCAL_HOST: String = "127.0.0.1"
public const val PRIVILEGE_ADB_DEFAULT_TCP_PORT: Int = 5555
internal const val PRIVILEGE_ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS: Long = 60_000L
internal const val PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_COUNT: Int = 5
internal const val PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS: Long = 1_000L
internal const val PRIVILEGE_ADB_DEFAULT_PORT_DISCOVERY_TIMEOUT_MILLIS: Long = 15_000L

internal data class PrivilegeAdbEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
    }

    override fun toString(): String = "$host:$port"

    val isLocalHost: Boolean
        get() = host == PRIVILEGE_ADB_LOCAL_HOST

    companion object {
        fun local(port: Int): PrivilegeAdbEndpoint =
            PrivilegeAdbEndpoint(PRIVILEGE_ADB_LOCAL_HOST, port)
    }
}

internal data class PrivilegeAdbStartResult(
    val endpoint: PrivilegeAdbEndpoint,
    val outputText: String,
    val identity: PrivilegeAdbIdentity,
    val publicKeyFingerprint: String = "",
) {
    val port: Int
        get() = endpoint.port
}

public data class PrivilegeAdbPairingResult public constructor(
    public val port: Int,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
)

public data class PrivilegeAdbPairingCheckResult public constructor(
    public val port: Int?,
    public val paired: Boolean,
    public val outputText: String,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
    public val failureMessage: String? = null,
    public val status: PrivilegeAdbPairingCheckStatus =
        if (paired) PrivilegeAdbPairingCheckStatus.PAIRED else PrivilegeAdbPairingCheckStatus.UNPAIRED,
)

public enum class PrivilegeAdbPairingCheckStatus {
    PAIRED,
    UNPAIRED,
    UNAVAILABLE,
    ERROR,
}

public data class PrivilegeAdbTcpResult public constructor(
    public val port: Int,
    public val outputText: String,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
)

public enum class PrivilegeAdbAuthorizationStatus {
    AUTHORIZED,
    UNAUTHORIZED,
    UNAVAILABLE,
    ERROR,
}

public data class PrivilegeAdbAuthorizationCheckResult public constructor(
    public val status: PrivilegeAdbAuthorizationStatus,
    public val outputText: String,
    public val identity: PrivilegeAdbIdentity,
    public val publicKeyFingerprint: String = "",
    public val failureMessage: String? = null,
)

public enum class PrivilegeAdbAuthorizationEndReason {
    AUTOMATIC_TIMEOUT,
    MANUAL_CANCELLED,
    FAILED,
}

public data class PrivilegeAdbAuthorizationRequestResult public constructor(
    public val authorized: Boolean,
    public val endReason: PrivilegeAdbAuthorizationEndReason? = null,
    public val outputText: String = "",
    public val failureMessage: String? = null,
)

public fun interface PrivilegeAdbAuthorizationRequestCallback {
    public fun onResult(result: PrivilegeAdbAuthorizationRequestResult)
}

internal object PrivilegeAdbPortSelector {
    fun chooseStartEndpoint(
        explicitPort: Int?,
        activeTcpPort: Int,
        tcpMode: Boolean,
        targetTcpPort: Int,
        discoveredEndpoint: PrivilegeAdbEndpoint?,
    ): PrivilegeAdbEndpoint {
        explicitPort?.let { return PrivilegeAdbEndpoint.local(it) }
        if (tcpMode && activeTcpPort > 0) return PrivilegeAdbEndpoint.local(activeTcpPort)
        discoveredEndpoint?.let { return it }
        if (tcpMode) return PrivilegeAdbEndpoint.local(targetTcpPort)
        throw PrivilegeAdbException("ADB port is not available")
    }

}
