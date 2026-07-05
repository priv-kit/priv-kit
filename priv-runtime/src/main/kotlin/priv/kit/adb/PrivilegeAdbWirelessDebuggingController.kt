package priv.kit.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

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

    override fun prepareAdb() {
        enforceWriteSecureSettingsPermission()
        Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
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
        runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                )
            }
            packageInfo.requestedPermissions
                ?.contains(Manifest.permission.WRITE_SECURE_SETTINGS) == true
        }.getOrDefault(false)

    private fun enforceWriteSecureSettingsPermission() {
        if (!hasWriteSecureSettingsDeclaration()) {
            throw PrivilegeAdbException("WRITE_SECURE_SETTINGS must be declared to manage Wireless debugging")
        }
        if (!hasWriteSecureSettingsPermission()) {
            throw PrivilegeAdbException("WRITE_SECURE_SETTINGS is required to manage Wireless debugging")
        }
    }

    internal companion object {
        internal const val ADB_WIFI_ENABLED: String = "adb_wifi_enabled"
        internal const val ADB_ALLOWED_CONNECTION_TIME: String = "adb_allowed_connection_time"
    }
}
