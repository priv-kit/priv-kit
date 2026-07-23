package priv.kit.core.internal.server

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.internal.core.PrivilegeProtocol
import java.io.File

class PrivilegeServerArgumentsTest {
    @Test
    fun parseInfersConfigFromClasspath() {
        val apk = testApk("example.app-hash")

        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = apk.path,
            initialLaunchId = null,
            uid = Process.SHELL_UID,
        )

        assertEquals("", config.token)
        assertNull(config.initialLaunchId)
        assertEquals("example.app", config.packageName)
        assertEquals(0, config.userId)
        assertEquals(apk.path, config.classpath)
        assertEquals(PrivilegeProtocol.VERSION, config.protocolVersion)
        assertEquals(PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS, config.followDeathDelayMillis)
        assertEquals(
            PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
            config.activeReconnectOnOwnerDeath,
        )
    }

    @Test
    fun parseInfersUserIdFromApplicationUid() {
        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = testApk("example.user-hash").path,
            uid = 1_012_345,
        )

        assertEquals(10, config.userId)
    }

    @Test
    fun parseRetainsInitialLaunchIdFromEnvironmentInput() {
        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = testApk("example.launch-hash").path,
            initialLaunchId = "launch-1",
            uid = Process.SHELL_UID,
        )

        assertEquals("launch-1", config.initialLaunchId)
    }

    @Test
    fun parseRejectsLaunchArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                args = arrayOf("--token", "token"),
                classpath = testApk("example.args-hash").path,
                uid = Process.SHELL_UID,
            )
        }
    }

    @Test
    fun parseRejectsBlankClasspath() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                args = emptyArray(),
                classpath = " ",
                uid = Process.SHELL_UID,
            )
        }
    }

    private fun testApk(installDirectoryName: String): File {
        val directory = File("build/tmp/serverArgs/$installDirectoryName").also { it.mkdirs() }
        return File(directory, "base.apk").also {
            it.writeText("apk")
        }
    }
}
