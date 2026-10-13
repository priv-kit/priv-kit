package priv.kit.ui

import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeUiViewModelUtilsTest {
    @Test
    fun scriptCommandKeepsSimplePathsShort() {
        assertEquals(
            "adb shell sh /sdcard/Android/data/example.app/files/sh/start.sh",
            privilegeUiManualShellScriptCommand(
                "example.app", "files/sh/start.sh",
            ),
        )
    }

    @Test
    fun scriptCommandPreservesQuotingThroughPosixHostAndDeviceShells() {
        assertEquals(
            """adb shell sh ''"'"'/sdcard/Android/data/example.app/files/my scripts/start.sh'"'"''""",
            privilegeUiManualShellScriptCommand(
                "example.app", "files/my scripts/start.sh",
            ),
        )
    }

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
    fun hostShellCommandKeepsAndroidLinkerCommand() {
        val nativeStarterCommand =
            "/system/bin/linker64 '/data/app/base.apk!/lib/arm64-v8a/libprivkitstarter.so'"

        assertEquals(
            "adb shell $nativeStarterCommand",
            privilegeUiManualShellCommand(nativeStarterCommand),
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
