package priv.kit.sample.debug

import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PRIVILEGE_ADB_DEFAULT_TCP_PORT
import priv.kit.sample.R

internal sealed interface PrivilegeSampleDebugDestination {
    val titleRes: Int

    data object Connection : PrivilegeSampleDebugDestination {
        override val titleRes: Int = R.string.sample_test_authorization
    }

    data object Binder : PrivilegeSampleDebugDestination {
        override val titleRes: Int = R.string.sample_test_binder
    }

    data object UserService : PrivilegeSampleDebugDestination {
        override val titleRes: Int = R.string.sample_test_userservice
    }

    data object Permissions : PrivilegeSampleDebugDestination {
        override val titleRes: Int = R.string.sample_test_permissions
    }

    companion object {
        val entries: List<PrivilegeSampleDebugDestination> = listOf(Connection, Binder, UserService, Permissions)
    }
}

internal sealed interface PrivilegeStartupTab {
    val titleRes: Int

    data object Root : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_root
    }

    data object Manual : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_manual
    }

    data object Shizuku : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_shizuku
    }

    data object WirelessAdb : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_wireless
    }

    data object Tcp : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_tcp
    }

    data object Log : PrivilegeStartupTab {
        override val titleRes: Int = R.string.sample_log
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

internal data class PrivilegeSampleSystemServicePresence(
    val exists: Boolean?,
    val error: String?,
)

internal data class PrivilegeSampleSystemServiceCheckResult(
    val serviceName: String,
    val currentProcess: PrivilegeSampleSystemServicePresence,
    val serverProcess: PrivilegeSampleSystemServicePresence,
)

internal enum class PrivilegeAdbPairingStatus(val labelRes: Int) {
    NOT_PAIRED(R.string.sample_not_paired),
    CHECKING(R.string.sample_checking),
    SEARCHING(R.string.sample_searching),
    FOUND(R.string.sample_port_found),
    PAIRING(R.string.sample_pairing),
    PAIRED(R.string.sample_paired),
    FAILED(R.string.sample_failed),
}

internal data class PrivilegeSampleScreenState(
    val busy: Boolean = false,
    val status: PrivilegeSampleStatus = PrivilegeSampleStatus.DISCONNECTED,
    val serverInfo: PrivilegeServerInfo? = null,
    val deniedPermissionsLoading: Boolean = false,
    val deniedPermissions: List<String>? = null,
    val deniedPermissionsError: String? = null,
    val manualShellCommandLine: String? = null,
    val adbDeviceNameText: String = "",
    val adbDeviceName: String = "",
    val adbKeyFingerprint: String? = null,
    val adbKeyFingerprintLoading: Boolean = false,
    val pairingPortText: String = "",
    val connectPortText: String = "",
    val pairingCode: String = "",
    val pairingStatus: PrivilegeAdbPairingStatus = PrivilegeAdbPairingStatus.NOT_PAIRED,
    val pairingMessage: String = "",
    val notificationPairingRunning: Boolean = false,
    val tcpPortText: String = PRIVILEGE_ADB_DEFAULT_TCP_PORT.toString(),
    val systemServiceNameText: String = "",
    val systemServiceCheckResult: PrivilegeSampleSystemServiceCheckResult? = null,
    val systemServiceBinderCached: Boolean = false,
    val userManagerCached: Boolean = false,
    val mqsNativeLocalDescriptor: String? = null,
    val mqsNativeLocalError: String? = null,
    val mqsNativeRemoteDescriptor: String? = null,
    val mqsNativeRemoteError: String? = null,
    val binderMessage: String = "",
    val binderLastException: String = "",
    val dedicatedUserServiceBound: Boolean = false,
    val embeddedUserServiceBound: Boolean = false,
    val dedicatedUserServiceCached: Boolean = false,
    val embeddedUserServiceCached: Boolean = false,
    val dedicatedUserServiceMessage: String = "-",
    val embeddedUserServiceMessage: String = "-",
    val userServiceMessage: String = "",
    val userServiceLastException: String = "",
    val shizukuReady: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val shizukuUid: Int? = null,
    val shizukuVersion: Int? = null,
    val shizukuMessage: String = "",
    val shizukuLastException: String = "",
    val message: String = "",
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
        appendLine("adbKeySource=persisted-adb-key")
        appendLine("adbKeyFingerprint=${adbKeyFingerprint ?: "not loaded"}")
        appendLine("pairingPort=${pairingPortText.ifBlank { "auto" }}")
        appendLine("connectPort=${connectPortText.ifBlank { "auto" }}")
        appendLine("tcpPort=${tcpPortText.ifBlank { "blank" }}")
        appendLine(
            "systemServiceName=" +
                (systemServiceCheckResult?.serviceName ?: systemServiceNameText.ifBlank { "none" }),
        )
        appendLine(
            "systemServiceCurrentProcess=" +
                systemServiceCheckResult?.currentProcess.toLogText(),
        )
        appendLine(
            "systemServiceServerProcess=" +
                systemServiceCheckResult?.serverProcess.toLogText(),
        )
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

private fun PrivilegeSampleSystemServicePresence?.toLogText(): String =
    when {
        this == null -> "not checked"
        error != null -> "error: $error"
        exists == true -> "present"
        exists == false -> "missing"
        else -> "not checked"
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
