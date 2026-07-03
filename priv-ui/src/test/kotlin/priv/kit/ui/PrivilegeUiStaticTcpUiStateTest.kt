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
    fun closedStaticPortHidesMainActionAndShowsCommandHelp() {
        assertFalse(
            staticTcpActionEnabled(
                tcpModeEnabled = false,
                busy = false,
                status = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
        assertFalse(staticTcpActionVisible(tcpModeEnabled = false))
        assertTrue(staticTcpCommandHelpVisible(tcpModeEnabled = false))
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
        assertTrue(staticTcpActionVisible(tcpModeEnabled = true))
        assertFalse(staticTcpCommandHelpVisible(tcpModeEnabled = true))
        assertEquals(
            R.string.priv_ui_adb_static_start_action,
            staticTcpActionLabel(
                tcpModeEnabled = true,
                status = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            ),
        )
    }
}
