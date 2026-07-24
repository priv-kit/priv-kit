package priv.kit.ui

import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiViewModelUtilsTest {
    @Test
    fun hostShellCommandAddsAdbShellPrefix() {
        assertEquals(
            "adb shell /data/app/libprivkitstarter.so",
            "/data/app/libprivkitstarter.so".toPrivilegeUiHostAdbShellCommand(),
        )
    }

    @Test
    fun hostShellCommandDoesNotDuplicateAdbShellPrefix() {
        assertEquals(
            "adb shell /data/app/libprivkitstarter.so",
            "adb shell /data/app/libprivkitstarter.so".toPrivilegeUiHostAdbShellCommand(),
        )
    }

    @Test
    fun staticTcpOpenCommandUsesConfiguredPort() {
        assertEquals(
            "adb tcpip 5555",
            privilegeUiStaticTcpOpenCommand(5555),
        )
    }
}
