package priv.kit.core.internal.server

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
            launchCorrelationId = null,
            ownerUserId = null,
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
    fun parseUsesExplicitOwnerUserId() {
        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = testApk("example.user-hash").path,
            launchCorrelationId = null,
            ownerUserId = "10",
        )

        assertEquals(10, config.userId)
    }

    @Test
    fun parseRetainsLaunchCorrelationIdFromEnvironmentInput() {
        val config = PrivilegeServerArguments.parse(
            args = emptyArray(),
            classpath = testApk("example.launch-hash").path,
            launchCorrelationId = "launch-1",
            ownerUserId = "0",
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
                ownerUserId = "0",
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
                ownerUserId = "0",
            )
        }
    }

    @Test
    fun parseRejectsInvalidOwnerUserId() {
        listOf("", "-1", "not-a-number").forEach { ownerUserId ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivilegeServerArguments.parse(
                    args = emptyArray(),
                    classpath = testApk("example.invalid-user-hash").path,
                    launchCorrelationId = null,
                    ownerUserId = ownerUserId,
                )
            }
        }
    }

    private fun testApk(installDirectoryName: String): File {
        val directory = File("build/tmp/serverArgs/$installDirectoryName").also { it.mkdirs() }
        return File(directory, "base.apk").also {
            it.writeText("apk")
        }
    }
}
