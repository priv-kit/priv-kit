package priv.kit.ui

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

public sealed interface PrivilegeUiServerRestartRequest {
    public data object Root : PrivilegeUiServerRestartRequest
    public data object Adb : PrivilegeUiServerRestartRequest
    public data object WirelessAdb : PrivilegeUiServerRestartRequest
    public data object StaticTcpAdb : PrivilegeUiServerRestartRequest
    public data class External public constructor(
        public val providerId: String,
    ) : PrivilegeUiServerRestartRequest
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
    val statusLoaded: Boolean = false,
)

internal data class PrivilegeUiStaticTcpState(
    val activePort: Int? = null,
    val configuredPort: Int? = null,
    val authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus =
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
    val loaded: Boolean = false,
)
