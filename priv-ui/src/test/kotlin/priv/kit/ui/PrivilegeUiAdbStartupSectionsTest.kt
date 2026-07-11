package priv.kit.ui

import priv.kit.ui.adb.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiAdbStartupSectionsTest {
    @Test
    fun supportedWirelessAndTcpPolicyShowsBothSections() {
        assertEquals(
            listOf(
                PrivilegeUiAdbStartupSection.WIRELESS,
                PrivilegeUiAdbStartupSection.STATIC_TCP,
            ),
            privilegeUiAdbStartupSections(
                wirelessAdbSupported = true,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun unsupportedWirelessShowsOnlyStaticTcpSection() {
        assertEquals(
            listOf(PrivilegeUiAdbStartupSection.STATIC_TCP),
            privilegeUiAdbStartupSections(
                wirelessAdbSupported = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun disabledTcpAndSupportedWirelessShowsWirelessSection() {
        assertEquals(
            listOf(PrivilegeUiAdbStartupSection.WIRELESS),
            privilegeUiAdbStartupSections(
                wirelessAdbSupported = true,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun disabledTcpAndUnsupportedWirelessShowsNoSection() {
        assertEquals(
            emptyList<PrivilegeUiAdbStartupSection>(),
            privilegeUiAdbStartupSections(
                wirelessAdbSupported = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }
}
