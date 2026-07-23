package priv.kit.ui.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiManualShellActionsTest {
    @Test
    fun missingExternalFilesDirectoryUsesDirectStarterCommand() {
        val command = createPrivilegeUiManualShellCommand(
            directShellCommand = "/data/app/example/lib/arm64/libprivkitstarter.so",
            externalBootstrapSupported = true,
            externalFilesDirectory = null,
            scriptWriter = { _, _ -> error("writer must not be called") },
        )

        assertEquals(
            "adb shell /data/app/example/lib/arm64/libprivkitstarter.so",
            command,
        )
    }

    @Test
    fun unsupportedExternalBootstrapUsesDirectStarterCommand() {
        val directory = Files.createTempDirectory("priv-kit-manual-shell-unsupported").toFile()
        try {
            val command = createPrivilegeUiManualShellCommand(
                directShellCommand = "/data/app/example/lib/arm64/libprivkitstarter.so",
                externalBootstrapSupported = false,
                externalFilesDirectory = directory,
                scriptWriter = { _, _ -> error("writer must not be called") },
            )

            assertEquals(
                "adb shell /data/app/example/lib/arm64/libprivkitstarter.so",
                command,
            )
            assertFalse(directory.resolve("priv-kit.sh").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun availableExternalFilesDirectoryReceivesBootstrapScript() {
        val directory = Files.createTempDirectory("priv-kit-manual-shell").toFile()
        try {
            val command = createPrivilegeUiManualShellCommand(
                directShellCommand = "/data/app/example/lib/arm64/libprivkitstarter.so",
                externalBootstrapSupported = true,
                externalFilesDirectory = directory,
            )

            val scriptFile = directory.resolve("priv-kit.sh")
            assertEquals(
                "#!/system/bin/sh\n" +
                    "exec /data/app/example/lib/arm64/libprivkitstarter.so\n",
                scriptFile.readText(StandardCharsets.UTF_8),
            )
            assertTrue(command.startsWith("adb shell sh "))
            assertTrue(command.contains("priv-kit.sh"))
            assertFalse(directory.resolve("priv-kit.sh.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bootstrapPreservesQuotedNativeStarterCommand() {
        val directory = Files.createTempDirectory("priv-kit-manual-shell-quoted").toFile()
        try {
            createPrivilegeUiManualShellCommand(
                directShellCommand = "'/data/app/example path/libprivkitstarter.so'",
                externalBootstrapSupported = true,
                externalFilesDirectory = directory,
            )

            assertEquals(
                "#!/system/bin/sh\n" +
                    "exec '/data/app/example path/libprivkitstarter.so'\n",
                directory.resolve("priv-kit.sh").readText(StandardCharsets.UTF_8),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
