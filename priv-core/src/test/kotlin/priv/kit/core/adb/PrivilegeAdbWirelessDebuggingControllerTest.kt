package priv.kit.core.adb

import android.Manifest
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbWirelessDebuggingControllerTest {
    @Before
    fun grantSettingsPermission() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0).apply {
            requestedPermissions = arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS)
        }
        Shadows.shadowOf(context.packageManager).installPackage(packageInfo)
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    @Test
    fun rejectedWritesIdentifySettingAndValue() {
        val context = RuntimeEnvironment.getApplication()
        val controller = AndroidPrivilegeAdbWirelessDebuggingController(
            context,
            writeGlobalInt = { _, _ -> false },
        )
        val attempts = listOf<Pair<String, () -> Unit>>(
            "adb_enabled=1" to { controller.enableAdb() },
            "adb_wifi_enabled=1" to { controller.setWirelessDebuggingEnabled(true) },
            "adb_wifi_enabled=0" to { controller.setWirelessDebuggingEnabled(false) },
        )
        attempts.forEach { (setting, action) ->
            val failure = assertThrows(PrivilegeAdbException::class.java) { action() }
            assertTrue(failure.message.orEmpty().contains(setting))
        }
    }

    @Test
    fun preparationStopsAtRejectedWrite() {
        val context = RuntimeEnvironment.getApplication()
        val writes = mutableListOf<String>()
        var rejectAdb = true
        val controller = AndroidPrivilegeAdbWirelessDebuggingController(
            context,
            writeGlobalInt = { name, value ->
                writes += "$name=$value"
                !rejectAdb
            },
            writeGlobalLong = { name, value ->
                writes += "$name=$value"
                false
            },
        )
        assertThrows(PrivilegeAdbException::class.java) { controller.prepareAdb() }
        assertEquals(listOf("adb_enabled=1"), writes)

        writes.clear()
        rejectAdb = false
        val failure = assertThrows(PrivilegeAdbException::class.java) { controller.prepareAdb() }
        assertEquals(listOf("adb_enabled=1", "adb_allowed_connection_time=0"), writes)
        assertTrue(failure.message.orEmpty().contains("adb_allowed_connection_time=0"))
    }

    @Test
    fun systemWriteExceptionIsPreserved() {
        val denied = SecurityException("denied by system")
        val controller = AndroidPrivilegeAdbWirelessDebuggingController(
            RuntimeEnvironment.getApplication(),
            writeGlobalInt = { _, _ -> throw denied },
        )

        assertSame(denied, assertThrows(SecurityException::class.java) { controller.enableAdb() })
    }

    @Test
    fun acceptedWriteDoesNotRequireReadableAdbState() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        val writes = mutableListOf<Pair<String, Int>>()
        // Model an accepted provider write whose real value is hidden from this app.
        val controller = AndroidPrivilegeAdbWirelessDebuggingController(
            context,
            writeGlobalInt = { name, value ->
                writes += name to value
                true
            },
        )

        controller.enableAdb()

        assertEquals(listOf(Settings.Global.ADB_ENABLED to 1), writes)
        assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1))
    }

    @Test
    fun alreadyEnabledWirelessDebuggingIsNotTemporarilyManagedForStart() {
        val status = PrivilegeAdbWirelessDebuggingControlStatus(
            supported = true,
            permissionDeclared = true,
            permissionGranted = true,
            wirelessDebuggingEnabled = true,
            canManage = true,
        )

        assertFalse(
            shouldEnableWirelessDebuggingForStart(
                PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
                status,
            ),
        )
        assertFalse(
            shouldRejectWirelessDebuggingForStart(
                PrivilegeAdbWirelessDebuggingControl.REQUIRE,
                status.copy(
                    permissionGranted = false,
                    canManage = false,
                ),
            ),
        )
    }

    @Test
    fun startManagementOnlyChangesDisabledWirelessDebugging() {
        val disabledManageable = PrivilegeAdbWirelessDebuggingControlStatus(
            supported = true,
            permissionDeclared = true,
            permissionGranted = true,
            wirelessDebuggingEnabled = false,
            canManage = true,
        )
        val disabledUnmanageable = disabledManageable.copy(
            permissionGranted = false,
            canManage = false,
        )

        assertTrue(
            shouldEnableWirelessDebuggingForStart(
                PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
                disabledManageable,
            ),
        )
        assertFalse(
            shouldEnableWirelessDebuggingForStart(
                PrivilegeAdbWirelessDebuggingControl.NEVER,
                disabledManageable,
            ),
        )
        assertTrue(
            shouldRejectWirelessDebuggingForStart(
                PrivilegeAdbWirelessDebuggingControl.REQUIRE,
                disabledUnmanageable,
            ),
        )
    }

    @Test
    fun staticTcpRecoveryOnlyEnablesCoreAdbService() {
        val context = RuntimeEnvironment.getApplication()
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
