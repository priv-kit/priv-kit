package priv.kit.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.core.internal.runtime.PrivilegeServerLaunchCommandBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeNativeStarterCommandTest {
    @Test
    fun publicNativeStarterCommandCachesInstalledLibrary() {
        installRuntimeContext()

        val nativeStarterCommand = Privilege.nativeStarterCommand

        assertTrue(PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID !in nativeStarterCommand)
        assertTrue(PrivilegeHandshakeContract.ENV_OWNER_USER_ID !in nativeStarterCommand)

        RuntimeEnvironment.getApplication().applicationInfo.nativeLibraryDir =
            "/data/app/reinstalled/lib/arm64"

        assertEquals(nativeStarterCommand, Privilege.nativeStarterCommand)
    }

    @Test
    fun coordinatedNativeStarterCommandIncludesLaunchCorrelationEnvironment() {
        installRuntimeContext()
        val baseNativeStarterCommand = Privilege.nativeStarterCommand

        val commandLine =
            Privilege.createNativeStarterCommand(
                launchCorrelationId = "launch-1",
            )

        assertEquals(
            "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=launch-1 " +
                baseNativeStarterCommand,
            commandLine,
        )
    }

    @Test
    fun nativeStarterCommandOmitsPrimaryOwnerUserScope() {
        assertEquals(
            "/starter",
            PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
                baseNativeStarterCommand = "/starter",
                launchCorrelationId = null,
                ownerUserId = 0,
            ),
        )
    }

    @Test
    fun nativeStarterCommandUsesExplicitNonPrimaryOwnerUserScope() {
        assertEquals(
            "${PrivilegeHandshakeContract.ENV_OWNER_USER_ID}=10 /starter",
            PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
                baseNativeStarterCommand = "/starter",
                launchCorrelationId = null,
                ownerUserId = 10,
            ),
        )
    }

    private fun installRuntimeContext() {
        val application = RuntimeEnvironment.getApplication()
        val nativeLibraryDir = File(application.cacheDir, "native-starter-path-test/arm64")
            .apply { mkdirs() }
        File(nativeLibraryDir, "libprivkitstarter.so")
            .apply { writeBytes(byteArrayOf(1)) }
        application.applicationInfo.nativeLibraryDir = nativeLibraryDir.path.replace('\\', '/')
        PrivilegeContext.install(application)
    }
}
