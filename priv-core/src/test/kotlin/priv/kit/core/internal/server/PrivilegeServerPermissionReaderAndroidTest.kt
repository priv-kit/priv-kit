package priv.kit.core.internal.server

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PrivilegeServerPermissionReaderAndroidTest {
    @Test
    @Config(sdk = [32, 33])
    fun readsDeclaredPermissionsAcrossPackageManagerApis() {
        val application = RuntimeEnvironment.getApplication() as Application
        val packageInfo = PackageInfo().apply {
            packageName = SERVER_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = SERVER_PACKAGE
                uid = SERVER_UID
            }
            requestedPermissions = arrayOf(GRANTED_PERMISSION, DENIED_PERMISSION)
        }
        shadowOf(application.packageManager).apply {
            installPackage(packageInfo)
            setPackagesForUid(SERVER_UID, SERVER_PACKAGE)
        }
        shadowOf(application).apply {
            grantPermissions(SERVER_PID, SERVER_UID, GRANTED_PERMISSION)
            denyPermissions(SERVER_PID, SERVER_UID, DENIED_PERMISSION)
        }

        assertEquals(
            listOf(DENIED_PERMISSION),
            PrivilegeServerPermissionReader.from(application).getDeniedPermissions(
                pid = SERVER_PID,
                uid = SERVER_UID,
            ),
        )
    }

    private companion object {
        const val SERVER_PACKAGE = "priv.kit.test.shell"
        const val SERVER_PID = 1234
        const val SERVER_UID = 2000
        const val GRANTED_PERMISSION = "priv.kit.permission.GRANTED"
        const val DENIED_PERMISSION = "priv.kit.permission.DENIED"
    }
}
