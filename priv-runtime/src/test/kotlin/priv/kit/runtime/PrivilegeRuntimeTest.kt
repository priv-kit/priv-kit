package priv.kit.runtime

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
}
