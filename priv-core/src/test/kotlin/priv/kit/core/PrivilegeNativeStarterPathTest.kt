package priv.kit.core

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
class PrivilegeNativeStarterPathTest {
    @Test
    fun publicNativeStarterPathCachesInstalledLibrary() {
        installRuntimeContext()

        val nativeStarterPath = Privilege.nativeStarterPath

        assertEquals(
            "/data/app/priv.kit.sample/lib/arm64/libprivkitstarter.so",
            nativeStarterPath,
        )
        assertFalse(
            nativeStarterPath.contains(PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID),
        )

        RuntimeEnvironment.getApplication().applicationInfo.nativeLibraryDir =
            "/data/app/reinstalled/lib/arm64"

        assertEquals(nativeStarterPath, Privilege.nativeStarterPath)
    }

    @Test
    fun coordinatedNativeStarterCommandIncludesLaunchCorrelationEnvironment() {
        installRuntimeContext()

        val commandLine =
            Privilege.createNativeStarterCommandWithLaunchCorrelationId("launch-1")

        assertEquals(
            "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=launch-1 " +
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
