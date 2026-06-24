package priv.kit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.binder.PrivilegeServerDisconnectedException
import java.io.File

class PrivilegeRuntimeTest {
    @Test
    fun getServerInfoWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            PrivilegeRuntime.getServerInfo()
        }
    }

    @Test
    fun requireBinderEndpointWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            PrivilegeRuntime.requireBinderEndpoint()
        }
    }

    @Test
    fun classpathIdentityIncludesPathSizeAndModifiedSeconds() {
        val directory = File("build/tmp/classpathIdentityTest").also { it.mkdirs() }
        val apk = File(directory, "base.apk").also {
            it.writeText("apk")
        }

        val identity = PrivilegeServerLaunchCommandBuilder.buildClasspathIdentity(apk.path)

        assertTrue(identity.contains(apk.path))
        assertTrue(identity.contains("@${apk.length()}@${apk.lastModified() / 1000L}"))
    }

    @Test
    fun userIdIsDerivedFromAndroidUidRange() {
        assertEquals(0, PrivilegeServerLaunchCommandBuilder.userIdFromUid(10_123))
        assertEquals(10, PrivilegeServerLaunchCommandBuilder.userIdFromUid(1_012_345))
    }

    @Test
    fun shortNativeStarterCommandUsesStarterPathOnly() {
        val commandLine = PrivilegeRuntime.buildShortNativeStarterCommand(
            starterPath = "/data/app/example/lib/arm64/libprivkitstarter.so",
        )

        assertEquals(
            "/data/app/example/lib/arm64/libprivkitstarter.so",
            commandLine,
        )
        assertFalse(commandLine.contains("token-value"))
        assertFalse(commandLine.contains("--user-id"))
    }
}
