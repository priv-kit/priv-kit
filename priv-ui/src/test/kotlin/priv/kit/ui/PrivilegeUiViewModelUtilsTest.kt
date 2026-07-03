package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiViewModelUtilsTest {
    @Test
    fun staticTcpCommandOpensPortBeforeShellStart() {
        assertEquals(
            "adb tcpip 5555; adb shell /data/app/libprivkitstarter.so",
            "/data/app/libprivkitstarter.so".toPrivilegeUiHostAdbStaticTcpCommand(5555),
        )
    }

    @Test
    fun staticTcpCommandDoesNotDuplicateAdbShellPrefix() {
        assertEquals(
            "adb tcpip 5555; adb shell /data/app/libprivkitstarter.so",
            "adb shell /data/app/libprivkitstarter.so".toPrivilegeUiHostAdbStaticTcpCommand(5555),
        )
    }
}
