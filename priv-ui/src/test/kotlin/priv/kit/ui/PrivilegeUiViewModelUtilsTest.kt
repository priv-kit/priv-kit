package priv.kit.ui

import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiViewModelUtilsTest {
    @Test
    fun hostShellCommandAddsAdbShellPrefix() {
        assertEquals(
            "adb shell /data/app/libprivkitstarter.so",
            privilegeUiManualShellCommand("/data/app/libprivkitstarter.so"),
        )
    }

    @Test
    fun hostShellCommandTrimsNativeStarterPath() {
        assertEquals(
            "adb shell /data/app/libprivkitstarter.so",
            privilegeUiManualShellCommand(" /data/app/libprivkitstarter.so "),
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
