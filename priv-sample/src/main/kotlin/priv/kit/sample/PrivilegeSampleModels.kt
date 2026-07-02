package priv.kit.sample

import priv.kit.PrivilegeServerInfo
import priv.kit.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT

internal sealed interface PrivilegeSampleDestination {
    val title: String

    data object Connection : PrivilegeSampleDestination {
        override val title: String = "Test Authorization"
    }

    data object Binder : PrivilegeSampleDestination {
        override val title: String = "Test Binder"
    }

    data object UserService : PrivilegeSampleDestination {
        override val title: String = "Test UserService"
    }

    data object PrivilegeUi : PrivilegeSampleDestination {
        override val title: String = "Authorization UI"
    }

    companion object {
        val entries: List<PrivilegeSampleDestination> = listOf(Connection, Binder, UserService)
    }
}

internal sealed interface PrivilegeStartupTab {
    val title: String

    data object Root : PrivilegeStartupTab {
        override val title: String = "Root"
    }

    data object Manual : PrivilegeStartupTab {
        override val title: String = "Manual"
    }

    data object Shizuku : PrivilegeStartupTab {
        override val title: String = "Shizuku"
    }

    data object WirelessAdb : PrivilegeStartupTab {
        override val title: String = "Wireless"
    }

    data object Tcp : PrivilegeStartupTab {
        override val title: String = "TCP"
    }

    data object Log : PrivilegeStartupTab {
        override val title: String = "Log"
    }

    companion object {
        val entries: List<PrivilegeStartupTab> = listOf(
            Root,
            Manual,
            Shizuku,
            WirelessAdb,
            Tcp,
            Log,
        )
    }
}

internal enum class PrivilegeSampleStatus {
    CONNECTED,
    DISCONNECTED,
    STARTING,
}

internal enum class PrivilegeAdbPairingStatus(val label: String) {
    NOT_PAIRED("Not paired"),
    CHECKING("Checking"),
    SEARCHING("Searching"),
    FOUND("Port found"),
    PAIRING("Pairing"),
    PAIRED("Paired"),
    FAILED("Failed"),
}

internal data class PrivilegeSampleScreenState(
    val busy: Boolean = false,
    val status: PrivilegeSampleStatus = PrivilegeSampleStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val manualShellCommandLine: String? = null,
    val adbDeviceNameText: String = "",
    val adbDeviceName: String = "",
    val adbKeyFingerprint: String? = null,
    val adbKeyFingerprintLoading: Boolean = false,
    val pairingPortText: String = "",
    val connectPortText: String = "",
    val pairingCode: String = "",
    val pairingStatus: PrivilegeAdbPairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
    val pairingMessage: String = "Enter the Wireless debugging pairing code, or reply from the pairing notification.",
    val tcpPortText: String = PRIVILEGE_ADB_DEFAULT_TCP_PORT.toString(),
    val systemServiceBinderCached: Boolean = false,
    val userManagerCached: Boolean = false,
    val mqsNativeLocalDescriptor: String? = null,
    val mqsNativeLocalError: String? = null,
    val mqsNativeRemoteDescriptor: String? = null,
    val mqsNativeRemoteError: String? = null,
    val binderMessage: String = "Connect to a Privileged Server, then get IUserManager or probe IMQSNative.",
    val binderLastException: String = "",
    val dedicatedUserServiceBound: Boolean = false,
    val embeddedUserServiceBound: Boolean = false,
    val dedicatedUserServiceCached: Boolean = false,
    val embeddedUserServiceCached: Boolean = false,
    val dedicatedUserServiceMessage: String = "-",
    val embeddedUserServiceMessage: String = "-",
    val userServiceMessage: String = "Connect to a Privileged Server, then bind a UserService.",
    val userServiceLastException: String = "",
    val shizukuReady: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val shizukuUid: Int? = null,
    val shizukuVersion: Int? = null,
    val shizukuMessage: String = "Shizuku status not checked",
    val shizukuLastException: String = "",
    val message: String = "Ready",
    val logText: String = "",
)

internal fun PrivilegeSampleScreenState.wirelessDebugLogText(): String =
    buildString {
        appendLine("Priv Kit Wireless ADB diagnostics")
        appendLine("busy=$busy")
        appendLine("runtimeStatus=$status")
        appendLine("message=$message")
        appendLine("pairingStatus=$pairingStatus")
        appendLine("pairingMessage=$pairingMessage")
        appendLine("adbDeviceNameConfigured=${adbDeviceNameText.ifBlank { "app-name" }}")
        appendLine("adbDeviceName=$adbDeviceName")
        appendLine("adbKeySource=owner-token")
        appendLine("adbKeyFingerprint=${adbKeyFingerprint ?: "not loaded"}")
        appendLine("pairingPort=${pairingPortText.ifBlank { "auto" }}")
        appendLine("connectPort=${connectPortText.ifBlank { "auto" }}")
        appendLine("tcpPort=${tcpPortText.ifBlank { "blank" }}")
        appendLine("systemServiceBinderCached=$systemServiceBinderCached")
        appendLine("userManagerCached=$userManagerCached")
        appendLine("mqsNativeLocal=${mqsNativeLocalDescriptor ?: mqsNativeLocalError ?: "none"}")
        appendLine("mqsNativeRemote=${mqsNativeRemoteDescriptor ?: mqsNativeRemoteError ?: "none"}")
        appendLine("binderMessage=$binderMessage")
        appendLine("dedicatedUserServiceBound=$dedicatedUserServiceBound")
        appendLine("embeddedUserServiceBound=$embeddedUserServiceBound")
        appendLine("dedicatedUserServiceCached=$dedicatedUserServiceCached")
        appendLine("embeddedUserServiceCached=$embeddedUserServiceCached")
        appendLine("userServiceMessage=$userServiceMessage")
        appendLine("shizukuReady=$shizukuReady")
        appendLine("shizukuPermissionGranted=$shizukuPermissionGranted")
        appendLine("shizukuUid=${shizukuUid ?: "none"}")
        appendLine("shizukuVersion=${shizukuVersion ?: "none"}")
        appendLine("shizukuMessage=$shizukuMessage")
        appendLine("serverInfo=${serverInfo ?: "none"}")
        appendLine()
        appendLine("Session log:")
        appendLine(logText.ifBlank { "<empty>" })
    }

internal fun Throwable.toDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(8).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

internal fun String?.toSampleAdbDeviceName(): String? {
    val value = this
        ?.replace('\u0000', ' ')
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.trim()
        ?.take(128)
    return value?.ifBlank { null }
}
