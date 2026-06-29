package priv.kit.internal.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeRuntimeNativeStarterDefaultsTest {
    @Test
    fun nativeStarterDoesNotAcceptServerLaunchArguments() {
        val source = nativeStarterSource().readText()

        assertTrue(source.contains("priv-kit starter does not accept arguments"))
        assertFalse(source.contains("--token"))
        assertFalse(source.contains("--provider-authority"))
        assertFalse(source.contains("--protocol-version"))
        assertFalse(source.contains("--follow-death-delay-millis"))
        assertFalse(source.contains("--active-reconnect-on-owner-death"))
    }

    @Test
    fun nativeStarterPrintsHumanReadableStartupStages() {
        val source = nativeStarterSource().readText()

        assertTrue(source.contains("info: starter begin"))
        assertFalse(source.contains("info: killing existing server"))
        assertTrue(source.contains("info: killed existing server pid="))
        assertTrue(source.contains("info: starting server"))
        assertFalse(source.contains("priv-kit-starter-pid="))
        assertFalse(source.contains("priv-kit-server-log="))
        assertFalse(source.contains("priv-kit-server-manual-"))
        assertFalse(source.contains("priv-kit-starter child pid="))
        assertTrue(source.contains("open(\"/dev/null\", O_RDWR)"))
        assertTrue(source.contains("info: starter exit with 0"))
    }

    private fun nativeStarterSource(): File =
        listOf(
            File("src/main/cpp/privilege_runtime_starter.cpp"),
            File("priv-runtime/src/main/cpp/privilege_runtime_starter.cpp"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to find privilege_runtime_starter.cpp")
}
