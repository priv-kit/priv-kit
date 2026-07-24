package priv.kit.core.internal.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.shared.PRIVILEGE_INTERNAL_SHELL_UID
import java.io.File

class PrivilegeServerArgumentsTest {
    @Test
    fun parseInfersConfigFromClasspath() {
        val apk = testApk("example.app-hash")

        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = apk.path,
            launchCorrelationId = null,
            uid = PRIVILEGE_INTERNAL_SHELL_UID,
        )

        assertNull(config.launchCorrelationId)
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
            launchCorrelationId = null,
            uid = 1_012_345,
        )

        assertEquals(10, config.userId)
    }

    @Test
    fun parseRetainsLaunchCorrelationIdFromEnvironmentInput() {
        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = testApk("example.launch-hash").path,
            launchCorrelationId = "launch-1",
            uid = PRIVILEGE_INTERNAL_SHELL_UID,
        )

        assertEquals("launch-1", config.launchCorrelationId)
    }

    @Test
    fun parseRejectsLaunchArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                args = arrayOf("--token", "token"),
                classpath = testApk("example.args-hash").path,
                launchCorrelationId = null,
                uid = PRIVILEGE_INTERNAL_SHELL_UID,
            )
        }
    }

    @Test
    fun parseRejectsBlankClasspath() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeServerArguments.parse(
                args = emptyArray(),
                classpath = " ",
                launchCorrelationId = null,
                uid = PRIVILEGE_INTERNAL_SHELL_UID,
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
