package priv.kit.runtime

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

    private fun nativeStarterSource(): File =
        listOf(
            File("src/main/cpp/privilege_runtime_starter.cpp"),
            File("priv-runtime/src/main/cpp/privilege_runtime_starter.cpp"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to find privilege_runtime_starter.cpp")
}
