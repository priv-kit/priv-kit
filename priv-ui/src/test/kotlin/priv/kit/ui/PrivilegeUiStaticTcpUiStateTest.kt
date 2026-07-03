package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiStaticTcpUiStateTest {
    @Test
    fun closedStaticPortShowsUnknownAuthorization() {
        assertEquals(
            PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            staticTcpAuthorizationDisplayStatus(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun closedStaticPortDisablesMainActionAndPromptsOtherMethod() {
        assertFalse(
            staticTcpActionEnabled(
                tcpModeEnabled = false,
                busy = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
        assertEquals(
            R.string.priv_ui_adb_static_use_other_method_action,
            staticTcpActionLabel(
                tcpModeEnabled = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun readyStaticPortKeepsStartActionEnabled() {
        assertTrue(
            staticTcpActionEnabled(
                tcpModeEnabled = true,
                busy = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            ),
        )
        assertEquals(
            R.string.priv_ui_adb_static_start_action,
            staticTcpActionLabel(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            ),
        )
    }
}
