package priv.kit.core.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import priv.kit.shared.PrivilegeManifestPermissions

public enum class PrivilegeAdbWirelessDebuggingControl {
    NEVER,
    IF_AVAILABLE,
    REQUIRE,
}

public data class PrivilegeAdbWirelessDebuggingControlStatus public constructor(
    public val supported: Boolean,
    public val permissionDeclared: Boolean,
    public val permissionGranted: Boolean,
    public val wirelessDebuggingEnabled: Boolean,
    public val canManage: Boolean,
    public val failureMessage: String? = null,
)

internal interface PrivilegeAdbWirelessDebuggingController {
    fun status(): PrivilegeAdbWirelessDebuggingControlStatus
    fun enableAdb()
    fun prepareAdb()
    fun setWirelessDebuggingEnabled(enabled: Boolean)
}

internal class AndroidPrivilegeAdbWirelessDebuggingController(
    private val context: Context,
) : PrivilegeAdbWirelessDebuggingController {
    private val contentResolver get() = context.contentResolver

    override fun status(): PrivilegeAdbWirelessDebuggingControlStatus {
        val permissionDeclared = hasWriteSecureSettingsDeclaration()
        val permissionGranted = permissionDeclared && hasWriteSecureSettingsPermission()
        return runCatching {
            val wirelessDebuggingEnabled = Settings.Global.getInt(
                contentResolver,
                ADB_WIFI_ENABLED,
                0,
            ) == 1
            PrivilegeAdbWirelessDebuggingControlStatus(
                supported = true,
                permissionDeclared = permissionDeclared,
                permissionGranted = permissionGranted,
                wirelessDebuggingEnabled = wirelessDebuggingEnabled,
                canManage = permissionGranted,
            )
        }.getOrElse { throwable ->
            PrivilegeAdbWirelessDebuggingControlStatus(
                supported = true,
                permissionDeclared = permissionDeclared,
                permissionGranted = permissionGranted,
                wirelessDebuggingEnabled = false,
                canManage = false,
                failureMessage = throwable.toFailureMessage(),
            )
        }
    }

    override fun enableAdb() {
        enforceWriteSecureSettingsPermission()
        Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
    }

    override fun prepareAdb() {
        enableAdb()
        Settings.Global.putLong(contentResolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
    }

    override fun setWirelessDebuggingEnabled(enabled: Boolean) {
        enforceWriteSecureSettingsPermission()
        Settings.Global.putInt(contentResolver, ADB_WIFI_ENABLED, if (enabled) 1 else 0)
    }

    private fun hasWriteSecureSettingsPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasWriteSecureSettingsDeclaration(): Boolean =
        PrivilegeManifestPermissions.isDeclared(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        )

    private fun enforceWriteSecureSettingsPermission() {
        if (!hasWriteSecureSettingsDeclaration()) {
            throw PrivilegeAdbException("WRITE_SECURE_SETTINGS must be declared to manage ADB")
        }
        if (!hasWriteSecureSettingsPermission()) {
            throw PrivilegeAdbException("WRITE_SECURE_SETTINGS is required to manage ADB")
        }
    }

    internal companion object {
        internal const val ADB_WIFI_ENABLED: String = "adb_wifi_enabled"
        internal const val ADB_ALLOWED_CONNECTION_TIME: String = "adb_allowed_connection_time"
    }
}

internal fun shouldEnableWirelessDebuggingForStart(
    control: PrivilegeAdbWirelessDebuggingControl,
    status: PrivilegeAdbWirelessDebuggingControlStatus,
): Boolean =
    control != PrivilegeAdbWirelessDebuggingControl.NEVER &&
        !status.wirelessDebuggingEnabled &&
        status.canManage

internal fun shouldRejectWirelessDebuggingForStart(
    control: PrivilegeAdbWirelessDebuggingControl,
    status: PrivilegeAdbWirelessDebuggingControlStatus,
): Boolean =
    control == PrivilegeAdbWirelessDebuggingControl.REQUIRE &&
        !status.wirelessDebuggingEnabled &&
        !status.canManage

internal fun disableManagedWirelessDebuggingAfterStart(
    shouldDisable: Boolean,
    controller: PrivilegeAdbWirelessDebuggingController?,
    output: PrivilegeAdbOutput,
) {
    if (!shouldDisable || controller == null) return
    runCatching {
        controller.setWirelessDebuggingEnabled(false)
        output.append("adb", "Wireless debugging disabled")
    }.onFailure { throwable ->
        output.append("diag", "Failed to disable Wireless debugging: ${throwable.toFailureMessage()}")
    }
}
