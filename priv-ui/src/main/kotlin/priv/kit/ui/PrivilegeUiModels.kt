package priv.kit.ui

import android.content.Context
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException
import priv.kit.delegate.PrivilegeDelegateExecutor
import priv.kit.runtime.PrivilegeRuntime

public enum class PrivilegeUiRuntimeStatus {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    FAILED,
}

public enum class PrivilegeUiStartupMode {
    ROOT,
    MANUAL_SHELL,
    ADB,
    DELEGATE,
}

public enum class PrivilegeUiAdbTcpPolicy {
    DISABLED,
    PREFER_EXISTING,
    AUTO_ENABLE_AFTER_WIRELESS_PAIRED,
}

public enum class PrivilegeUiAdbPairingStatus {
    NOT_PAIRED,
    CHECKING,
    SEARCHING,
    FOUND,
    PAIRING,
    PAIRED,
    FAILED,
}

public enum class PrivilegeUiWirelessAdbStatus {
    UNKNOWN,
    CHECKING,
    ON,
    OFF,
}

public data class PrivilegeUiConfig public constructor(
    public val startupModes: Set<PrivilegeUiStartupMode> = setOf(
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
        PrivilegeUiStartupMode.ROOT,
    ),
    public val delegateProviders: List<PrivilegeUiDelegateProvider> = emptyList(),
    public val adbDeviceName: String? = null,
    public val tcpPort: Int = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT,
    public val adbTcpPolicy: PrivilegeUiAdbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    public val wirelessStatusPollIntervalMillis: Long = DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS,
    public val wirelessStatusDiscoveryTimeoutMillis: Long = DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS,
    public val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    public val followDeathDelayMillis: Long = PrivilegeRuntime.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    public val activeReconnectOnOwnerDeath: Boolean = PrivilegeRuntime.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
) {
    init {
        require(startTimeoutMillis > 0L) { "startTimeoutMillis must be positive" }
        require(followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(wirelessStatusPollIntervalMillis > 0L) {
            "wirelessStatusPollIntervalMillis must be positive"
        }
        require(wirelessStatusDiscoveryTimeoutMillis > 0L) {
            "wirelessStatusDiscoveryTimeoutMillis must be positive"
        }
        require(delegateProviders.map { it.id }.distinct().size == delegateProviders.size) {
            "delegate provider ids must be unique"
        }
    }

    public companion object {
        public const val DEFAULT_START_TIMEOUT_MILLIS: Long = 15_000L
        public const val DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS: Long = 3_000L
        public const val DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS: Long = 1_500L
    }
}

public interface PrivilegeUiDelegateProvider {
    public val id: String

    public val label: CharSequence

    public fun snapshot(context: Context): PrivilegeUiDelegateSnapshot =
        PrivilegeUiDelegateSnapshot()

    public fun requestAuthorization(context: Context): PrivilegeUiDelegateSnapshot =
        snapshot(context)

    @Throws(PrivilegeStartupException::class)
    public fun createExecutor(context: Context): PrivilegeDelegateExecutor
}

public data class PrivilegeUiDelegateSnapshot public constructor(
    public val available: Boolean = false,
    public val authorized: Boolean = false,
    public val uid: Int? = null,
    public val version: Int? = null,
    public val message: CharSequence = "",
    public val exceptionText: String = "",
) {
    public val canStart: Boolean
        get() = available && authorized
}

public data class PrivilegeUiDelegateItemState public constructor(
    public val id: String,
    public val label: CharSequence,
    public val snapshot: PrivilegeUiDelegateSnapshot = PrivilegeUiDelegateSnapshot(),
    public val busy: Boolean = false,
)

public data class PrivilegeUiState public constructor(
    public val busy: Boolean = false,
    public val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
    public val serverInfo: PrivilegeServerInfo? = null,
    public val selectedStartupMode: PrivilegeUiStartupMode = PrivilegeUiStartupMode.ADB,
    public val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
        PrivilegeUiStartupMode.ROOT,
    ),
    public val message: String = "",
    public val manualShellCommandLine: String? = null,
    public val pairingCode: String = "",
    public val pairingStatus: PrivilegeUiAdbPairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
    public val pairingMessage: String = "",
    public val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessStatusPollingActive: Boolean = false,
    public val notificationPairingRunning: Boolean = false,
    public val delegateItems: List<PrivilegeUiDelegateItemState> = emptyList(),
    public val connectionSerial: Long = 0L,
)

internal fun String.toPrivilegeUiPairingCodeDigits(): String =
    filter(Char::isDigit).take(PRIVILEGE_UI_PAIRING_CODE_LENGTH)

internal const val PRIVILEGE_UI_PAIRING_CODE_LENGTH = 6
