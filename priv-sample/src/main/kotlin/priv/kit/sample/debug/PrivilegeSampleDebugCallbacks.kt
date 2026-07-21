package priv.kit.sample.debug

internal data class PrivilegeSampleDebugCallbacks(
    val openPrivilegeUi: () -> Unit,
    val backToHome: () -> Unit,
    val destinationSelected: (PrivilegeSampleDebugDestination) -> Unit,
    val startupTabSelected: (PrivilegeStartupTab) -> Unit,
    val connection: PrivilegeSampleConnectionCallbacks,
    val binder: PrivilegeSampleBinderCallbacks,
    val userService: PrivilegeSampleUserServiceCallbacks,
    val log: PrivilegeSampleLogCallbacks,
)

internal data class PrivilegeSampleConnectionCallbacks(
    val adbDeviceNameChanged: (String) -> Unit,
    val refreshAdbFingerprint: () -> Unit,
    val checkAdbPairing: () -> Unit,
    val pairingCodeChanged: (String) -> Unit,
    val tcpPortChanged: (String) -> Unit,
    val startRootRuntime: () -> Unit,
    val copyManualCommand: () -> Unit,
    val startShizukuExternal: () -> Unit,
    val pairWirelessAdb: () -> Unit,
    val startNotificationPairing: () -> Unit,
    val stopNotificationPairing: () -> Unit,
    val startWirelessAdb: () -> Unit,
    val switchToTcp: () -> Unit,
    val restartTcp: () -> Unit,
    val stopTcp: () -> Unit,
    val stopServer: () -> Unit,
)

internal data class PrivilegeSampleBinderCallbacks(
    val getUserManager: () -> Unit,
    val getUsers: () -> Unit,
    val runImqsNative: () -> Unit,
)

internal data class PrivilegeSampleUserServiceCallbacks(
    val bindDedicated: () -> Unit,
    val callDedicated: () -> Unit,
    val stopDedicated: () -> Unit,
    val bindEmbedded: () -> Unit,
    val callEmbedded: () -> Unit,
    val stopEmbedded: () -> Unit,
)

internal data class PrivilegeSampleLogCallbacks(
    val clear: () -> Unit,
    val copy: () -> Unit,
)
