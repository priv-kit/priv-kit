package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeUiAdbStartupTabsTest {
    @Test
    fun supportedWirelessAndTcpPolicyShowsBothTabs() {
        assertEquals(
            listOf(
                PrivilegeUiAdbStartupTab.WIRELESS,
                PrivilegeUiAdbStartupTab.STATIC_TCP,
            ),
            privilegeUiAdbStartupTabs(
                wirelessAdbSupported = true,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun unsupportedWirelessShowsOnlyStaticTcpTab() {
        assertEquals(
            listOf(PrivilegeUiAdbStartupTab.STATIC_TCP),
            privilegeUiAdbStartupTabs(
                wirelessAdbSupported = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun disabledTcpAndUnsupportedWirelessShowsNoTab() {
        assertEquals(
            emptyList<PrivilegeUiAdbStartupTab>(),
            privilegeUiAdbStartupTabs(
                wirelessAdbSupported = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun defaultTabPrefersStaticTcpWhenPortIsOpen() {
        assertEquals(
            PrivilegeUiAdbStartupTab.STATIC_TCP,
            defaultPrivilegeUiAdbStartupTab(
                tabs = listOf(
                    PrivilegeUiAdbStartupTab.WIRELESS,
                    PrivilegeUiAdbStartupTab.STATIC_TCP,
                ),
                tcpModeEnabled = true,
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun defaultTabPrefersWirelessBeforeStaticTcpIsReady() {
        assertEquals(
            PrivilegeUiAdbStartupTab.WIRELESS,
            defaultPrivilegeUiAdbStartupTab(
                tabs = listOf(
                    PrivilegeUiAdbStartupTab.WIRELESS,
                    PrivilegeUiAdbStartupTab.STATIC_TCP,
                ),
                tcpModeEnabled = false,
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun defaultTabIsNullWhenNoTabIsAvailable() {
        assertNull(
            defaultPrivilegeUiAdbStartupTab(
                tabs = emptyList(),
                tcpModeEnabled = false,
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            ),
        )
    }
}
