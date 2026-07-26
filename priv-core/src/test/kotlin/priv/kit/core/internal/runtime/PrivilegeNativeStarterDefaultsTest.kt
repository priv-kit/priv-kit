package priv.kit.core.internal.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeNativeStarterDefaultsTest {
    @Test
    fun nativeStarterUsesCurrentServerMainClass() {
        val source = nativeStarterSource().readText()
        val expected = "DEFAULT_MAIN_CLASS = \"${PrivilegeServerLaunchCommandBuilder.SERVER_MAIN_CLASS}\""

        assertTrue(source.contains(expected))
    }

    @Test
    fun serverMainClassSourceExists() {
        assertTrue(sourceFileFor(PrivilegeServerLaunchCommandBuilder.SERVER_MAIN_CLASS).isFile)
    }

    @Test
    fun nativeStarterAllowsOnlyRootSystemAndShellUids() {
        val source = nativeStarterSource().readText()

        assertTrue(source.contains("constexpr uid_t ROOT_UID = 0;"))
        assertTrue(source.contains("constexpr uid_t SYSTEM_UID = 1000;"))
        assertTrue(source.contains("constexpr uid_t SHELL_UID = 2000;"))
        assertTrue(
            source.contains(
                "return uid == ROOT_UID || uid == SYSTEM_UID || uid == SHELL_UID;",
            ),
        )
        assertTrue(source.contains("if (!is_supported_starter_uid(uid))"))
        assertTrue(source.contains("static_assert(!is_supported_starter_uid(1001));"))
    }

    private fun nativeStarterSource(): File =
        listOf(
            File("src/main/cpp/priv_kit_starter.cpp"),
            File("priv-core/src/main/cpp/priv_kit_starter.cpp"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to find priv_kit_starter.cpp")

    private fun sourceFileFor(className: String): File {
        val sourcePath = className.replace('.', File.separatorChar) + ".kt"
        return listOf(
            File("src/main/kotlin", sourcePath),
            File("priv-core/src/main/kotlin", sourcePath),
        ).firstOrNull(File::isFile)
            ?: error("Unable to find source for $className")
    }
}
