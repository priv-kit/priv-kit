package priv.kit.ui

import android.content.Context
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException
import priv.kit.delegate.PrivilegeDelegateExecutor
import priv.kit.runtime.PrivilegeRuntime

enum class PrivilegeUiRuntimeStatus {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    FAILED,
}

enum class PrivilegeUiStartupMode {
    ROOT,
    MANUAL_SHELL,
    ADB,
    @Deprecated("Use ADB. Wireless ADB is now part of the combined ADB authorization mode.")
    WIRELESS_ADB,
    @Deprecated("Use ADB. TCP is now part of the combined ADB authorization mode.")
    TCP,
    DELEGATE,
}

enum class PrivilegeUiAdbTcpPolicy {
    DISABLED,
    PREFER_EXISTING,
    AUTO_ENABLE_AFTER_WIRELESS_PAIRED,
}

enum class PrivilegeUiAdbPairingStatus {
    NOT_PAIRED,
    CHECKING,
    SEARCHING,
    FOUND,
    PAIRING,
    PAIRED,
    FAILED,
}

enum class PrivilegeUiWirelessAdbStatus {
    UNKNOWN,
    CHECKING,
    ON,
    OFF,
}

data class PrivilegeUiConfig(
    val startupModes: Set<PrivilegeUiStartupMode> = setOf(
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
        PrivilegeUiStartupMode.ROOT,
    ),
    val delegateProviders: List<PrivilegeUiDelegateProvider> = emptyList(),
    val adbDeviceName: String? = null,
    val tcpPort: Int = PrivilegeAdbStartOptions.DEFAULT_TCP_PORT,
    val adbTcpPolicy: PrivilegeUiAdbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    val wirelessStatusPollIntervalMillis: Long = DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS,
    val wirelessStatusDiscoveryTimeoutMillis: Long = DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS,
    val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    val followDeathDelayMillis: Long = PrivilegeRuntime.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeRuntime.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
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

    companion object {
        const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS = 3_000L
        const val DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS = 1_500L
    }
}

interface PrivilegeUiDelegateProvider {
    val id: String

    val label: CharSequence

    fun snapshot(context: Context): PrivilegeUiDelegateSnapshot =
        PrivilegeUiDelegateSnapshot()

    fun requestAuthorization(context: Context): PrivilegeUiDelegateSnapshot =
        snapshot(context)

    @Throws(PrivilegeStartupException::class)
    fun createExecutor(context: Context): PrivilegeDelegateExecutor
}

data class PrivilegeUiDelegateSnapshot(
    val available: Boolean = false,
    val authorized: Boolean = false,
    val uid: Int? = null,
    val version: Int? = null,
    val message: CharSequence = "",
    val exceptionText: String = "",
) {
    val canStart: Boolean
        get() = available && authorized
}

data class PrivilegeUiDelegateItemState(
    val id: String,
    val label: CharSequence,
    val snapshot: PrivilegeUiDelegateSnapshot = PrivilegeUiDelegateSnapshot(),
    val busy: Boolean = false,
)

data class PrivilegeUiState(
    val busy: Boolean = false,
    val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val selectedStartupMode: PrivilegeUiStartupMode = PrivilegeUiStartupMode.ADB,
    val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
        PrivilegeUiStartupMode.ROOT,
    ),
    val message: String = "",
    val manualShellCommandLine: String? = null,
    val pairingCode: String = "",
    val pairingStatus: PrivilegeUiAdbPairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
    val pairingMessage: String = "",
    val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    val wirelessStatusPollingActive: Boolean = false,
    val notificationPairingRunning: Boolean = false,
    val delegateItems: List<PrivilegeUiDelegateItemState> = emptyList(),
    val connectionSerial: Long = 0L,
)

internal fun String.toPrivilegeUiPairingCodeDigits(): String =
    filter(Char::isDigit).take(PRIVILEGE_UI_PAIRING_CODE_LENGTH)

internal const val PRIVILEGE_UI_PAIRING_CODE_LENGTH = 6
