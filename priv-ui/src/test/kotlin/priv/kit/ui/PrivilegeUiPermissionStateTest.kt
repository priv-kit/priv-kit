package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiPermissionStateTest {
    @Test
    fun grantedPermissionIsGranted() {
        assertEquals(
            PrivilegeUiPermissionState.Granted,
            privilegeUiPermissionState(
                granted = true,
                requested = false,
                shouldShowRequestPermissionRationale = false,
            ),
        )
    }

    @Test
    fun permissionNotRequestedYetIsDenied() {
        assertEquals(
            PrivilegeUiPermissionState.NotGranted.Denied,
            privilegeUiPermissionState(
                granted = false,
                requested = false,
                shouldShowRequestPermissionRationale = false,
            ),
        )
    }

    @Test
    fun deniedPermissionWithRationaleIsDenied() {
        assertEquals(
            PrivilegeUiPermissionState.NotGranted.Denied,
            privilegeUiPermissionState(
                granted = false,
                requested = true,
                shouldShowRequestPermissionRationale = true,
            ),
        )
    }

    @Test
    fun deniedPermissionWithoutRationaleIsPermanentlyDenied() {
        assertEquals(
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            privilegeUiPermissionState(
                granted = false,
                requested = true,
                shouldShowRequestPermissionRationale = false,
            ),
        )
    }

    @Test
    fun onlyDeniedPermissionLaunchesAnotherRequest() {
        assertFalse(
            PrivilegeUiPermissionState.Granted.shouldLaunchPermissionRequest(),
        )
        assertTrue(
            PrivilegeUiPermissionState.NotGranted.Denied.shouldLaunchPermissionRequest(),
        )
        assertFalse(
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied
                .shouldLaunchPermissionRequest(),
        )
    }
}
