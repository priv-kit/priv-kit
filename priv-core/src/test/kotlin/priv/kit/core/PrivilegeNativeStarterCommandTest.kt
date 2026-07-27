package priv.kit.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.runtime.PrivilegeContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeNativeStarterCommandTest {
    @Test
    fun publicNativeStarterCommandCachesInstalledLibrary() {
        installRuntimeContext()

        val nativeStarterCommand = Privilege.nativeStarterCommand

        assertFalse(
            nativeStarterCommand.contains(PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID),
        )

        RuntimeEnvironment.getApplication().applicationInfo.nativeLibraryDir =
            "/data/app/reinstalled/lib/arm64"

        assertEquals(nativeStarterCommand, Privilege.nativeStarterCommand)
    }

    @Test
    fun coordinatedNativeStarterCommandIncludesLaunchCorrelationEnvironment() {
        installRuntimeContext()
        val nativeStarterCommand = Privilege.nativeStarterCommand

        val commandLine =
            Privilege.createNativeStarterCommand(
                launchCorrelationId = "launch-1",
            )

        assertEquals(
            "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=launch-1 " +
                nativeStarterCommand,
            commandLine,
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
