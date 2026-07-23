package priv.kit.ui

import android.content.Context
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS
import priv.kit.shared.isPrivilegeAdbPort
import priv.kit.shared.toPrivilegeAdbPairingCodeDigits

internal enum class PrivilegeUiRuntimeStatus {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    FAILED,
}

internal enum class PrivilegeUiRuntimeStartPhase {
    IDLE,
    RUNNING,
    CANCELLING,
}

internal enum class PrivilegeUiPermissionRestrictionStatus {
    UNKNOWN,
    NOT_RESTRICTED,
    RESTRICTED,
}

public enum class PrivilegeUiStartupMode {
    ROOT,
    MANUAL_SHELL,
    ADB,
    EXTERNAL,
}

internal enum class PrivilegeUiRuntimeStartSource {
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

internal enum class PrivilegeUiAdbPairingStatus {
    NOT_PAIRED,
    CHECKING,
    SEARCHING,
    FOUND,
    PAIRING,
    PAIRED,
    FAILED,
}

internal enum class PrivilegeUiWirelessAdbStatus {
    UNKNOWN,
    CHECKING,
    ON,
    OFF,
}

internal enum class PrivilegeUiManagedWirelessAdbStatus {
    UNKNOWN,
    CHECKING,
    READY,
    UNDECLARED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    FAILED,
}

internal enum class PrivilegeUiAdbTcpAuthorizationStatus {
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
    public val adbAuthorizationTimeoutMillis: Long =
        PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS,
    public val wirelessStatusPollIntervalMillis: Long = DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS,
    public val wirelessStatusDiscoveryTimeoutMillis: Long = DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS,
    public val externalStartStatusPollIntervalMillis: Long = DEFAULT_EXTERNAL_START_STATUS_POLL_INTERVAL_MILLIS,
    public val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
) {
    init {
        require(startTimeoutMillis > 0L) { "startTimeoutMillis must be positive" }
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
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
        const val DEFAULT_START_TIMEOUT_MILLIS: Long =
            PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS
        const val DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS: Long = 3_000L
        const val DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS: Long = 1_500L
        const val DEFAULT_EXTERNAL_START_STATUS_POLL_INTERVAL_MILLIS: Long = 3_000L
    }
}

public interface PrivilegeUiExternalStartProvider {
    /** Stable identifier used to restore this provider across process restarts and app upgrades. */
    public val id: String

    public val label: CharSequence

    public suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        PrivilegeUiExternalStartSnapshot()

    public suspend fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot =
        snapshot(context)

    @Throws(PrivilegeStartupException::class)
    public suspend fun start(
        context: Context,
        commandLine: String,
    )
}

public interface PrivilegeUiStreamingExternalStartProvider : PrivilegeUiExternalStartProvider {
    @Throws(PrivilegeStartupException::class)
    public suspend fun start(
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

internal data class PrivilegeUiExternalStartItemState(
    val id: String,
    val label: CharSequence,
    val snapshot: PrivilegeUiExternalStartSnapshot = PrivilegeUiExternalStartSnapshot(),
)

internal data class PrivilegeUiState(
    val busy: Boolean = false,
    val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
    val runtimeStatusLoaded: Boolean = false,
    val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
    val serverInfo: PrivilegeServerInfo? = null,
    val selectedStartupMode: PrivilegeUiStartupMode = PrivilegeUiStartupMode.ADB,
    val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    ),
    val runtimeProgressText: PrivilegeUiText? = null,
    val manualShellCommandLine: String? = null,
    val manualShellStatusLoaded: Boolean = false,
    val pairingCode: String = "",
    val pairingStatus: PrivilegeUiAdbPairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
    val pairingText: PrivilegeUiText? = null,
    val pairingDialogVisible: Boolean = false,
    val pairingNotificationPermissionWarningVisible: Boolean = false,
    val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus =
        PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
    val wifiConnected: Boolean = false,
    val tcpModePort: Int? = null,
    val tcpAuthorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus =
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
    val adbKeyFingerprint: String? = null,
    val adbStatusLoaded: Boolean = false,
    val notificationPairingRunning: Boolean = false,
    val externalStartItems: List<PrivilegeUiExternalStartItemState> = emptyList(),
    val externalStartStatusLoaded: Boolean = false,
    val startupLogLines: List<String> = emptyList(),
    val connectionSerial: Long = 0L,
    val runtimeStartPhase: PrivilegeUiRuntimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
    val runtimeStartProviderId: String? = null,
    val configuredTcpModePort: Int? = null,
    val permissionRestrictionStatus: PrivilegeUiPermissionRestrictionStatus =
        PrivilegeUiPermissionRestrictionStatus.UNKNOWN,
    val desiredEnabled: Boolean = false,
)

internal fun String.toPrivilegeUiPairingCodeDigits(): String =
    toPrivilegeAdbPairingCodeDigits()
