package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeUiConfigTest {
    @Test
    fun manualScriptIsDisabledByDefaultAndAcceptsExplicitPaths() {
        assertNull(PrivilegeUiConfig().manualShellScriptPath)
        assertEquals(
            "files/start.sh",
            PrivilegeUiConfig(manualShellScriptPath = "files/start.sh").manualShellScriptPath,
        )
        assertEquals(
            "files/sh/start.sh",
            PrivilegeUiConfig(manualShellScriptPath = "files/sh/start.sh").manualShellScriptPath,
        )
        assertEquals("start.sh", PrivilegeUiConfig(manualShellScriptPath = "start.sh").manualShellScriptPath)
    }

    @Test
    fun manualScriptRejectsAbsoluteAndAmbiguousPaths() {
        listOf(
            "", " ", "/start.sh", "sh/", "sh//start.sh", ".", "..",
            "../start.sh", "sh/../../start.sh", "sh/./start.sh", "sh\\start.sh",
            "sh/start\n.sh", "sh/start\r.sh", "sh/start\u0000.sh",
        ).forEach { path ->
            assertThrows("Accepted invalid path: $path", IllegalArgumentException::class.java) {
                PrivilegeUiConfig(manualShellScriptPath = path)
            }
        }
    }

    @Test
    fun notificationPairingConfigIsValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingChannelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingNotificationId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingNotificationId = Int.MAX_VALUE)
        }
    }
}
