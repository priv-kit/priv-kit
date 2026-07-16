package priv.kit.ui

import android.content.Context
import priv.kit.adb.PRIVILEGE_ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS
import priv.kit.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeStartupException
import priv.kit.PrivilegeStartupLogListener
import priv.kit.PrivilegeConfig

public enum class PrivilegeUiRuntimeStatus {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    FAILED,
}

public enum class PrivilegeUiRuntimeStartPhase {
    IDLE,
    RUNNING,
    CANCELLING,
}

public enum class PrivilegeUiStartupMode {
    ROOT,
    MANUAL_SHELL,
    ADB,
    EXTERNAL,
}

public enum class PrivilegeUiRuntimeStartSource {
    ROOT,
    ADB_WIRELESS,
    ADB_STATIC_TCP,
    EXTERNAL,
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

public enum class PrivilegeUiManagedWirelessAdbStatus {
    UNKNOWN,
    CHECKING,
    READY,
    UNDECLARED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    FAILED,
}

internal fun PrivilegeUiManagedWirelessAdbStatus.isVisibleManagedWirelessAdbStatus(): Boolean =
    this != PrivilegeUiManagedWirelessAdbStatus.UNDECLARED

public enum class PrivilegeUiAdbTcpAuthorizationStatus {
    UNKNOWN,
    CHECKING,
    AUTHORIZING,
    AUTHORIZED,
    UNAUTHORIZED,
    UNAVAILABLE,
    FAILED,
}

public data class PrivilegeUiConfig public constructor(
    public val startupModes: Set<PrivilegeUiStartupMode> = setOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    ),
    public val externalStartProviders: List<PrivilegeUiExternalStartProvider> = emptyList(),
    public val adbDeviceName: String? = null,
    public val tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    public val adbTcpPolicy: PrivilegeUiAdbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    public val enableManagedWirelessAdb: Boolean = true,
    public val adbAuthorizationTimeoutMillis: Long = PRIVILEGE_ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
    public val wirelessStatusPollIntervalMillis: Long = DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS,
    public val wirelessStatusDiscoveryTimeoutMillis: Long = DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS,
    public val externalStartStatusPollIntervalMillis: Long = DEFAULT_EXTERNAL_START_STATUS_POLL_INTERVAL_MILLIS,
    public val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    public val followDeathDelayMillis: Long = PrivilegeConfig.followDeathDelayMillis,
    public val activeReconnectOnOwnerDeath: Boolean = PrivilegeConfig.activeReconnectOnOwnerDeath,
) {
    init {
        require(startTimeoutMillis > 0L) { "startTimeoutMillis must be positive" }
        require(followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
        require(tcpPort in 1..65535) { "tcpPort must be between 1 and 65535" }
        require(adbAuthorizationTimeoutMillis > 0L) { "adbAuthorizationTimeoutMillis must be positive" }
        require(wirelessStatusPollIntervalMillis > 0L) {
            "wirelessStatusPollIntervalMillis must be positive"
        }
        require(wirelessStatusDiscoveryTimeoutMillis > 0L) {
            "wirelessStatusDiscoveryTimeoutMillis must be positive"
        }
        require(externalStartStatusPollIntervalMillis > 0L) {
            "externalStartStatusPollIntervalMillis must be positive"
        }
        require(externalStartProviders.map { it.id }.distinct().size == externalStartProviders.size) {
            "external start provider ids must be unique"
        }
    }

    internal companion object {
        internal const val DEFAULT_START_TIMEOUT_MILLIS: Long = 15_000L
        internal const val DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS: Long = 3_000L
        internal const val DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS: Long = 1_500L
        internal const val DEFAULT_EXTERNAL_START_STATUS_POLL_INTERVAL_MILLIS: Long = 3_000L
    }
}

public interface PrivilegeUiExternalStartProvider {
    public val id: String

    public val label: CharSequence

    public fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        PrivilegeUiExternalStartSnapshot()

    public fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot =
        snapshot(context)

    @Throws(PrivilegeStartupException::class)
    public fun start(
        context: Context,
        commandLine: String,
    )
}

public interface PrivilegeUiStreamingExternalStartProvider : PrivilegeUiExternalStartProvider {
    @Throws(PrivilegeStartupException::class)
    public fun start(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    )
}

public data class PrivilegeUiExternalStartSnapshot public constructor(
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

public data class PrivilegeUiExternalStartItemState public constructor(
    public val id: String,
    public val label: CharSequence,
    public val snapshot: PrivilegeUiExternalStartSnapshot = PrivilegeUiExternalStartSnapshot(),
    public val busy: Boolean = false,
)

public data class PrivilegeUiState public constructor(
    public val busy: Boolean = false,
    public val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
    public val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
    public val serverInfo: PrivilegeServerInfo? = null,
    public val selectedStartupMode: PrivilegeUiStartupMode = PrivilegeUiStartupMode.ADB,
    public val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    ),
    public val runtimeProgressMessage: String? = null,
    public val manualShellCommandLine: String? = null,
    public val pairingCode: String = "",
    public val pairingStatus: PrivilegeUiAdbPairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
    public val pairingMessage: String = "",
    public val pairingDialogVisible: Boolean = false,
    public val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus =
        PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
    public val wifiConnected: Boolean = false,
    public val tcpModePort: Int? = null,
    public val tcpAuthorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus =
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
    public val wirelessStatusPollingActive: Boolean = false,
    public val tcpModeStatusPollingActive: Boolean = false,
    public val adbKeyFingerprint: String? = null,
    public val notificationPairingRunning: Boolean = false,
    public val externalStartItems: List<PrivilegeUiExternalStartItemState> = emptyList(),
    public val startupLogLines: List<String> = emptyList(),
    public val connectionSerial: Long = 0L,
    public val runtimeStartPhase: PrivilegeUiRuntimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
    public val runtimeStartProviderId: String? = null,
    public val configuredTcpModePort: Int? = null,
)

internal fun String.toPrivilegeUiPairingCodeDigits(): String =
    filter(Char::isDigit).take(PRIVILEGE_UI_PAIRING_CODE_LENGTH)

internal const val PRIVILEGE_UI_PAIRING_CODE_LENGTH = 6
