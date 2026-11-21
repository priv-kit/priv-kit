package priv.kit.ui

import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingIntentContract
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS
import priv.kit.shared.isPrivilegeAdbPort
import priv.kit.shared.toPrivilegeAdbPairingCodeDigits
import priv.kit.ui.runtime.requireValidManualShellScriptPath

public data class PrivilegeUiConfig public constructor(
    /**
     * Default methods, automatic startup order and observed sources; explicit start actions remain available.
     * Providers append EXTERNAL unless its position is specified. Effective modes must not be empty.
     */
    public val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    ),
    public val externalStartProviders: List<PrivilegeExternalStartProvider> = emptyList(),
    public val adbDeviceName: String? = null,
    public val tcpPort: Int = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    public val adbTcpPolicy: PrivilegeUiAdbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    public val enableManagedWirelessAdb: Boolean = true,
    /** Stable channel ID reserved by the host application for notification pairing. */
    public val notificationPairingChannelId: String =
        PrivilegeAdbPairingIntentContract.NOTIFICATION_CHANNEL_ID,
    /** First of two consecutive notification IDs reserved for notification pairing. */
    public val notificationPairingNotificationId: Int =
        PrivilegeAdbPairingIntentContract.NOTIFICATION_ID,
    /**
     * Maximum wait for the ADB key response after an active static TCP port is selected.
     * Wireless Debugging discovery, pairing, and static-port enablement are outside this timeout.
     */
    public val adbAuthorizationTimeoutMillis: Long =
        PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS,
    public val wirelessStatusPollIntervalMillis: Long = DEFAULT_WIRELESS_STATUS_POLL_INTERVAL_MILLIS,
    public val wirelessStatusDiscoveryTimeoutMillis: Long = DEFAULT_WIRELESS_STATUS_DISCOVERY_TIMEOUT_MILLIS,
    public val externalStartStatusPollIntervalMillis: Long = DEFAULT_EXTERNAL_START_STATUS_POLL_INTERVAL_MILLIS,
    public val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    /**
     * Defaults to `null`, using the full native starter command without writing an external script.
     * Other shell processes can modify external scripts before execution, causing altered code to
     * run with the executing caller's privileges. Only opt in if the host accepts this risk.
     * The recommended opt-in path is `priv.sh`.
     *
     * Startup script path relative to `Context.getExternalFilesDir(null)?.parentFile`, the app's
     * external `Android/data/<package>` directory, including the filename. For example,
     * `files/sh/priv.sh` uses the `files/sh` subdirectory; `priv.sh` sits alongside `files`.
     * The script command is displayed and can be copied immediately. The UI prepares the script in
     * the background once during ViewModel initialization, independently of silent startup. Matching
     * content is reused; missing or changed scripts are written, creating parent directories.
     * Switching tabs or resuming the page does not prepare it again. Successful preparation leaves
     * the displayed command unchanged.
     * Call [PrivilegeUi.prepareExternalShellScript] to prepare the script without opening UI.
     * Writes use the system-returned directory; the displayed command uses `/sdcard/Android/data`.
     * Absolute paths, empty components, `.` and `..`, backslashes, and control characters are
     * rejected.
     * Unavailable storage or a file read/write failure falls back to the full native starter command.
     * Nonzero Android users always use the full command without accessing external storage,
     * because the executing shell may not be able to read their external directories.
     * Use letters, digits, `/`, `.`, `_`, and `-` for portable desktop commands; other characters
     * use POSIX host-shell quoting.
     */
    public val manualShellScriptPath: String? = null,
) {
    init {
        require(effectiveStartupModes().isNotEmpty()) { "effective startup modes must not be empty" }
        requireValidManualShellScriptPath(manualShellScriptPath)
        require(startupModes.distinct().size == startupModes.size) {
            "startup modes must be unique"
        }
        require(startTimeoutMillis > 0L) { "startTimeoutMillis must be positive" }
        require(tcpPort.isPrivilegeAdbPort()) { "tcpPort must be between 1 and 65535" }
        require(notificationPairingChannelId.isNotBlank()) {
            "notificationPairingChannelId must not be blank"
        }
        require(notificationPairingNotificationId in 1 until Int.MAX_VALUE) {
            "notificationPairingNotificationId must be between 1 and ${Int.MAX_VALUE - 1}"
        }
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

@ExperimentalPrivilegeUiApi
public data class PrivilegeUiState(
    public val localNetworkPermissionMissing: Boolean = false,
    public val busy: Boolean = false,
    public val runtimeStatus: PrivilegeUiRuntimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
    public val runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
    public val serverInfo: PrivilegeServerInfo? = null,
    public val startupModes: List<PrivilegeUiStartupMode> = listOf(
        PrivilegeUiStartupMode.ROOT,
        PrivilegeUiStartupMode.ADB,
        PrivilegeUiStartupMode.MANUAL_SHELL,
    ),
    public val runtimeProgressText: PrivilegeUiText? = null,
    public val manualShellCommandLine: String? = null,
    public val pairingCode: String = "",
    public val pairingStatus: PrivilegeUiAdbPairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
    public val pairingText: PrivilegeUiText? = null,
    public val pairingInteraction: PrivilegeUiPairingInteraction? = null,
    public val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingServiceStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessPairingCheckStatus: PrivilegeUiWirelessAdbStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
    public val wirelessAdbStatusLoaded: Boolean = false,
    public val managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus =
        PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
    public val wifiConnected: Boolean = false,
    public val staticTcp: PrivilegeUiStaticTcpState = PrivilegeUiStaticTcpState(),
    public val tcpAuthorizationFailureSerial: Long = 0,
    public val adbKeyFingerprint: String? = null,
    public val notificationPairingRunning: Boolean = false,
    public val externalStartItems: List<PrivilegeUiExternalStartItemState> = emptyList(),
    public val startupLogLines: List<String> = emptyList(),
    public val connectionSerial: Long = 0L,
    public val deniedServerPermissions: List<String> = emptyList(),
    public val runtimeStartPhase: PrivilegeUiRuntimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
    public val runtimeStartProviderId: String? = null,
    public val permissionRestrictionStatus: PrivilegeUiPermissionRestrictionStatus =
        PrivilegeUiPermissionRestrictionStatus.UNKNOWN,
    public val desiredEnabled: Boolean = false,
    public val restartConfirmationTarget: PrivilegeUiServerRestartRequest? = null,
)

internal fun String.toPrivilegeUiPairingCodeDigits(): String =
    toPrivilegeAdbPairingCodeDigits()
