package priv.kit.core.internal.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeNativeStarterDefaultsTest {
    @Test
    fun nativeStarterDoesNotAcceptServerLaunchArguments() {
        val source = nativeStarterSource().readText()

        assertTrue(source.contains("priv-kit starter does not accept arguments"))
    }

    @Test
    fun nativeStarterPrintsHumanReadableStartupStages() {
        val source = nativeStarterSource().readText()

        assertTrue(source.contains("info: starter begin"))
        assertTrue(source.contains("info: killed existing server pid="))
        assertTrue(source.contains("info: starting server"))
        assertTrue(source.contains("open(\"/dev/null\", O_RDWR)"))
        assertTrue(source.contains("info: starter exit with 0"))
    }

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
