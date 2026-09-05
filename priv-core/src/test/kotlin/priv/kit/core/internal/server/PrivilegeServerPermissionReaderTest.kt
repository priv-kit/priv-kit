package priv.kit.core.internal.server

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeServerPermissionReaderTest {
    @Test
    fun returnsSortedDistinctDeniedPermissionsAcrossUidPackages() {
        val requestedPermissions = mapOf(
            "package.two" to arrayOf(
                "permission.DENIED_B",
                "permission.SHARED_DENIED",
            ),
            "package.one" to arrayOf(
                "permission.GRANTED",
                "permission.DENIED_A",
                "permission.SHARED_DENIED",
            ),
        )
        val permissionChecks = mutableListOf<PermissionCheck>()
        val reader = PrivilegeServerPermissionReader.createForTest(
            packagesForUid = { uid ->
                assertEquals(SHELL_UID, uid)
                arrayOf("package.two", "package.one", "package.one")
            },
            requestedPermissionsForPackage = requestedPermissions::get,
            checkPermission = { permission, pid, uid ->
                permissionChecks += PermissionCheck(permission, pid, uid)
                if (permission == "permission.GRANTED") {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
            },
        )

        assertEquals(
            listOf(
                "permission.DENIED_A",
                "permission.DENIED_B",
                "permission.SHARED_DENIED",
            ),
            reader.getDeniedPermissions(pid = SERVER_PID, uid = SHELL_UID),
        )
        assertEquals(
            setOf(
                "permission.DENIED_A",
                "permission.DENIED_B",
                "permission.GRANTED",
                "permission.SHARED_DENIED",
            ),
            permissionChecks.map(PermissionCheck::permission).toSet(),
        )
        assertFalse(permissionChecks.isEmpty())
        permissionChecks.forEach { check ->
            assertEquals(SERVER_PID, check.pid)
            assertEquals(SHELL_UID, check.uid)
        }
    }

    @Test
    fun nonRootUidWithoutAssociatedPackagesFails() {
        val reader = PrivilegeServerPermissionReader.createForTest(
            packagesForUid = { null },
            requestedPermissionsForPackage = { emptyArray() },
            checkPermission = { _, _, _ -> PackageManager.PERMISSION_DENIED },
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            reader.getDeniedPermissions(pid = SERVER_PID, uid = SHELL_UID)
        }

        assertEquals(
            "No packages are associated with privileged server uid $SHELL_UID",
            exception.message,
        )
    }

    private data class PermissionCheck(
        val permission: String,
        val pid: Int,
        val uid: Int,
    )

    private companion object {
        const val SERVER_PID = 1234
        const val SHELL_UID = 2000
    }
}
