package priv.kit.adb

import android.Manifest
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbWirelessDebuggingControllerTest {
    @Test
    fun staticTcpRecoveryOnlyEnablesCoreAdbService() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0).apply {
            requestedPermissions = arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS)
        }
        Shadows.shadowOf(context.packageManager).installPackage(packageInfo)
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putLong(
            resolver,
            AndroidPrivilegeAdbWirelessDebuggingController.ADB_ALLOWED_CONNECTION_TIME,
            12_345L,
        )
        Settings.Global.putInt(
            resolver,
            AndroidPrivilegeAdbWirelessDebuggingController.ADB_WIFI_ENABLED,
            0,
        )

        AndroidPrivilegeAdbWirelessDebuggingController(context).enableAdb()

        assertEquals(1, Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0))
        assertEquals(
            12_345L,
            Settings.Global.getLong(
                resolver,
                AndroidPrivilegeAdbWirelessDebuggingController.ADB_ALLOWED_CONNECTION_TIME,
                -1L,
            ),
        )
        assertEquals(
            0,
            Settings.Global.getInt(
                resolver,
                AndroidPrivilegeAdbWirelessDebuggingController.ADB_WIFI_ENABLED,
                -1,
            ),
        )
    }
}
