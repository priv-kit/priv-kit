package priv.kit.core.internal.server

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import priv.kit.shared.PrivilegeProcessPermissions

internal class PrivilegeServerPermissionReader private constructor(
    private val packagesForUid: (Int) -> Array<String>?,
    private val requestedPermissionsForPackage: (String) -> Array<String>?,
    private val isPermissionDefined: (String) -> Boolean,
    private val checkPermission: (String, Int, Int) -> Int,
) {
    fun getDeniedPermissions(
        pid: Int,
        uid: Int,
    ): List<String> {
        val packageNames = checkNotNull(packagesForUid(uid)) {
            "No packages are associated with privileged server uid $uid"
        }
        check(packageNames.isNotEmpty()) {
            "No packages are associated with privileged server uid $uid"
        }

        return packageNames
            .asSequence()
            .distinct()
            .flatMap { packageName ->
                requestedPermissionsForPackage(packageName).orEmpty().asSequence()
            }
            .distinct()
            .filter { permission ->
                checkPermission(permission, pid, uid) != PackageManager.PERMISSION_GRANTED
            }
            .filter(isPermissionDefined)
            .sorted()
            .toList()
    }

    companion object {
        fun from(context: Context): PrivilegeServerPermissionReader {
            val packageManager = context.packageManager
            return PrivilegeServerPermissionReader(
                packagesForUid = packageManager::getPackagesForUid,
                requestedPermissionsForPackage = { packageName ->
                    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(
                            packageName,
                            PackageManager.PackageInfoFlags.of(
                                PackageManager.GET_PERMISSIONS.toLong(),
                            ),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(
                            packageName,
                            PackageManager.GET_PERMISSIONS,
                        )
                    }
                    packageInfo.requestedPermissions
                },
                isPermissionDefined = { permission ->
                    try {
                        packageManager.getPermissionInfo(permission, 0)
                        true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }
                },
                checkPermission = PrivilegeProcessPermissions::check,
            )
        }

        internal fun createForTest(
            packagesForUid: (Int) -> Array<String>?,
            requestedPermissionsForPackage: (String) -> Array<String>?,
            isPermissionDefined: (String) -> Boolean,
            checkPermission: (String, Int, Int) -> Int,
        ): PrivilegeServerPermissionReader = PrivilegeServerPermissionReader(
            packagesForUid = packagesForUid,
            requestedPermissionsForPackage = requestedPermissionsForPackage,
            isPermissionDefined = isPermissionDefined,
            checkPermission = checkPermission,
        )
    }
}
