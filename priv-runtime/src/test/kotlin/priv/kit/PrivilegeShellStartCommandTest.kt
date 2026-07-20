package priv.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.runtime.PrivilegeContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeShellStartCommandTest {
    @Test
    fun publicShellStartCommandRemainsPlainStarterPath() {
        installRuntimeContext()

        val commandLine = Privilege.createShellStartCommand()

        assertEquals(
            "/data/app/priv.kit.sample/lib/arm64/libprivkitstarter.so",
            commandLine,
        )
        assertFalse(commandLine.contains(PrivilegeHandshakeContract.ENV_INITIAL_LAUNCH_ID))
    }

    @Test
    fun coordinatedShellStartCommandIncludesInitialLaunchEnvironment() {
        installRuntimeContext()

        val commandLine = Privilege.createShellStartCommandWithLaunchId("launch-1")

        assertEquals(
            "${PrivilegeHandshakeContract.ENV_INITIAL_LAUNCH_ID}=launch-1 " +
                "/data/app/priv.kit.sample/lib/arm64/libprivkitstarter.so",
            commandLine,
        )
    }

    private fun installRuntimeContext() {
        val application = RuntimeEnvironment.getApplication()
        application.applicationInfo.nativeLibraryDir =
            "/data/app/priv.kit.sample/lib/arm64"
        PrivilegeContext.install(application)
    }
}
