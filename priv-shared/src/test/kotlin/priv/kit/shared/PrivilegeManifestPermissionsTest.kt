package priv.kit.shared

import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PrivilegeManifestPermissionsTest {
    @Test
    @Config(sdk = [32, 33])
    fun readsDeclaredPermissionsAcrossPackageManagerApis() {
        val application = application()
        val packageInfo = application.packageManager
            .getPackageInfo(application.packageName, 0)
            .apply {
                requestedPermissions = arrayOf(DECLARED_PERMISSION)
            }
        shadowOf(application.packageManager).installPackage(packageInfo)

        assertTrue(PrivilegeManifestPermissions.isDeclared(application, DECLARED_PERMISSION))
        assertFalse(PrivilegeManifestPermissions.isDeclared(application, UNDECLARED_PERMISSION))
    }

    @Test
    @Config(sdk = [32, 33])
    fun returnsFalseWhenTheHostPackageCannotBeResolved() {
        val context = object : ContextWrapper(application()) {
            override fun getPackageName(): String = "missing.priv.kit.package"
        }

        assertFalse(PrivilegeManifestPermissions.isDeclared(context, DECLARED_PERMISSION))
    }

    private fun application(): Application =
        RuntimeEnvironment.getApplication() as Application

    private companion object {
        const val DECLARED_PERMISSION = "priv.kit.permission.DECLARED"
        const val UNDECLARED_PERMISSION = "priv.kit.permission.UNDECLARED"
    }
}
