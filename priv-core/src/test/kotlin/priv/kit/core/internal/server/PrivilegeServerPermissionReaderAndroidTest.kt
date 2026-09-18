package priv.kit.core.internal.server

import android.app.Application
import android.app.IActivityManager
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager
import java.lang.reflect.Proxy

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
            requestedPermissions = arrayOf(GRANTED_PERMISSION, DENIED_PERMISSION, UNKNOWN_PERMISSION)
        }
        shadowOf(application.packageManager).apply {
            addPermissionInfo(PermissionInfo().apply { name = GRANTED_PERMISSION })
            addPermissionInfo(PermissionInfo().apply { name = DENIED_PERMISSION })
            installPackage(packageInfo)
            setPackagesForUid(SERVER_UID, SERVER_PACKAGE)
        }
        shadowOf(application).apply {
            grantPermissions(SERVER_PID, SERVER_UID, GRANTED_PERMISSION)
            denyPermissions(SERVER_PID, SERVER_UID, DENIED_PERMISSION)
        }
        installPermissionService { permission ->
            if (permission == GRANTED_PERMISSION) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }

        assertEquals(
            listOf(DENIED_PERMISSION),
            PrivilegeServerPermissionReader.from(application).getDeniedPermissions(
                pid = SERVER_PID,
                uid = SERVER_UID,
            ),
        )
    }

    @Test
    @Config(sdk = [33])
    fun refreshUsesLiveServiceEvenWhenContextRetainsGrantedResult() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).apply {
            addPermissionInfo(PermissionInfo().apply { name = GRANTED_PERMISSION })
            installPackage(PackageInfo().apply {
                packageName = SERVER_PACKAGE
                applicationInfo = ApplicationInfo().apply { uid = SERVER_UID }
                requestedPermissions = arrayOf(GRANTED_PERMISSION)
            })
            setPackagesForUid(SERVER_UID, SERVER_PACKAGE)
        }
        val staleContext = object : ContextWrapper(application) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_GRANTED
        }
        var granted = true
        installPermissionService {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val reader = PrivilegeServerPermissionReader.from(staleContext)
        assertEquals(emptyList<String>(), reader.getDeniedPermissions(SERVER_PID, SERVER_UID))
        granted = false
        assertEquals(listOf(GRANTED_PERMISSION), reader.getDeniedPermissions(SERVER_PID, SERVER_UID))
        granted = true
        assertEquals(emptyList<String>(), reader.getDeniedPermissions(SERVER_PID, SERVER_UID))
    }

    private fun installPermissionService(check: (String) -> Int) {
        val service = Proxy.newProxyInstance(
            IActivityManager::class.java.classLoader,
            arrayOf(IActivityManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "checkPermission" -> {
                    assertEquals(SERVER_PID, args!![1])
                    assertEquals(SERVER_UID, args[2])
                    check(args[0] as String)
                }
                else -> null
            }
        } as IActivityManager
        ShadowServiceManager.addBinderService("activity", IActivityManager::class.java, service)
    }

    private companion object {
        const val SERVER_PACKAGE = "priv.kit.test.shell"
        const val SERVER_PID = 1234
        const val SERVER_UID = 2000
        const val GRANTED_PERMISSION = "priv.kit.permission.GRANTED"
        const val DENIED_PERMISSION = "priv.kit.permission.DENIED"
        const val UNKNOWN_PERMISSION = "priv.kit.permission.UNKNOWN"
    }
}
