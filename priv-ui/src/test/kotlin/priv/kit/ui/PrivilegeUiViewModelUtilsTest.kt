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
    fun hostShellScriptCommandUsesShAndQuotesUnsafePaths() {
        assertEquals(
            "adb shell sh '/storage/emulated/0/Android/data/app id/files/priv-kit.sh'",
            "/storage/emulated/0/Android/data/app id/files/priv-kit.sh"
                .toPrivilegeUiHostAdbShellScriptCommand(),
        )
    }

    @Test
    fun primaryExternalStoragePathUsesShortSdcardAlias() {
        assertEquals(
            "/sdcard/Android/data/priv.kit.sample/files/priv-kit.sh",
            "/storage/emulated/0/Android/data/priv.kit.sample/files/priv-kit.sh"
                .toPrivilegeUiAdbVisibleExternalPath(),
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
