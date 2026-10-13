package priv.kit.ui.runtime

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import priv.kit.ui.PrivilegeUiConfig

class PrivilegeUiExternalShellScriptTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val failures = mutableListOf<Exception>()

    @Test
    fun writesAndReturnsActualFileForExplicitPaths() {
        val appDirectory = temporaryFolder.newFolder("example.app")
        val filesDirectory = File(appDirectory, "files").apply { mkdirs() }
        listOf("files/start.sh", "start.sh", "files/sh/start.sh").forEach { path ->
            val script = preparePrivilegeUiExternalShellScript(
                nativeStarterCommand = { "/data/app/starter.so" },
                ownerUserId = 0,
                scriptPath = path,
                externalFilesDirectory = { filesDirectory },
                onScriptFailure = failures::add,
            )

            assertEquals("#!/system/bin/sh\n/data/app/starter.so\n", File(appDirectory, path).readText())
            assertEquals(File(appDirectory, path), script)
            assertTrue(script!!.isAbsolute)
        }
        assertFalse(File(filesDirectory, "files").exists())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun nonzeroUsersSkipNativeResolutionAndExternalStorage() {
        listOf(1, 10).forEach { userId ->
            val script = preparePrivilegeUiExternalShellScript(
                nativeStarterCommand = { error("Nonzero users must not resolve the native command") },
                ownerUserId = userId,
                scriptPath = "files/start.sh",
                externalFilesDirectory = { error("Nonzero users must not access external storage") },
                onScriptFailure = failures::add,
            )

            assertNull(script)
        }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun createsNestedScriptAndRefreshesInstallationPathsAtTheSameLocation() {
        val directory = temporaryFolder.newFolder("files")
        val oldCommand = "/data/app/old/lib/arm64/libprivkitstarter.so"
        val newCommand = "/system/bin/linker64 " +
            "'/data/app/new/base.apk!/lib/arm64-v8a/libprivkitstarter.so'"
        val first = prepare(oldCommand) { directory }
        val script = File(directory, "sh/start.sh")
        assertEquals("#!/system/bin/sh\n$oldCommand\n", script.readText())

        val refreshed = prepare(newCommand) { directory }

        assertEquals(first, refreshed)
        assertEquals(script, refreshed)
        assertEquals("#!/system/bin/sh\n$newCommand\n", script.readText())
        assertEquals(listOf("start.sh"), script.parentFile!!.list()!!.toList())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun reusesMatchingContentAndRepairsChangedOrMissingScripts() {
        val directory = temporaryFolder.newFolder("files")
        val script = checkNotNull(prepare { directory })
        val content = script.readText()
        assertTrue(script.setLastModified(1_000_000L))
        val unchangedTimestamp = script.lastModified()

        assertEquals(script, prepare { directory })
        assertEquals(unchangedTimestamp, script.lastModified())

        script.writeText(content.replace("starter.so", "altered.so"))
        assertEquals(script, prepare { directory })
        assertEquals(content, script.readText())

        assertTrue(script.delete())
        assertEquals(script, prepare { directory })
        assertEquals(content, script.readText())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun concurrentPreparationsReuseTheExistingScript() {
        val directory = temporaryFolder.newFolder("files")
        val script = checkNotNull(prepare { directory })
        assertTrue(script.setLastModified(1_000_000L))
        val unchangedTimestamp = script.lastModified()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(
                List(8) { Callable { prepare { directory } } },
                5,
                TimeUnit.SECONDS,
            )
            results.forEach { assertEquals(script, it.get()) }
            assertEquals(unchangedTimestamp, script.lastModified())
            assertEquals(listOf("start.sh"), script.parentFile!!.list()!!.toList())
            assertTrue(failures.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun defaultConfigSkipsNativeResolutionAndExternalStorage() {
        val script = preparePrivilegeUiExternalShellScript(
            nativeStarterCommand = { error("Disabled scripts must not resolve the native command") },
            ownerUserId = 0,
            scriptPath = PrivilegeUiConfig().manualShellScriptPath,
            externalFilesDirectory = { error("Storage must not be accessed") },
            onScriptFailure = failures::add,
        )

        assertNull(script)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun unavailableStorageReturnsNullAndCanRecoverOnRefresh() {
        assertNull(prepare { null })
        val directory = temporaryFolder.newFolder("files")

        assertEquals(File(directory, "sh/start.sh"), prepare { directory })
        assertTrue(File(directory, "sh/start.sh").isFile)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun inaccessibleStorageReturnsNullAndReportsFailure() {
        val failure = SecurityException("External storage unavailable")

        val script = prepare { throw failure }

        assertNull(script)
        assertEquals(listOf(failure), failures)
    }

    @Test
    fun cannotCreateParentDirectoryReturnsNullWithoutOverwritingItsFile() {
        val directory = temporaryFolder.newFolder("files")
        val parent = File(directory, "sh").apply { writeText("Existing file") }

        val script = prepare { directory }

        assertNull(script)
        assertEquals("Existing file", parent.readText())
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
    }

    @Test
    fun failedReplacementKeepsExistingDirectoryAndCleansTemporaryFile() {
        val directory = temporaryFolder.newFolder("files")
        val target = File(directory, "sh/start.sh").apply { mkdirs() }
        val existing = File(target, "keep").apply { writeText("Existing content") }

        val script = prepare { directory }

        assertNull(script)
        assertEquals("Existing content", existing.readText())
        assertEquals(listOf("start.sh"), target.parentFile!!.list()!!.toList())
        assertEquals(1, failures.size)
    }

    @Test
    fun refusesResolvedPathOutsideAppExternalDirectory() {
        val directory = temporaryFolder.newFolder("example.app")
        val filesDirectory = File(directory, "files").apply { mkdirs() }
        val outside = File(directory.parentFile, "start.sh")

        val script = preparePrivilegeUiExternalShellScript(
            nativeStarterCommand = { "/data/app/starter.so" },
            ownerUserId = 0,
            scriptPath = "../start.sh",
            externalFilesDirectory = { filesDirectory },
            onScriptFailure = failures::add,
        )

        assertNull(script)
        assertFalse(outside.exists())
        assertEquals(1, failures.size)
    }

    @Test(expected = IOException::class)
    fun nativeResolutionFailurePropagatesInsteadOfBecomingAWriteFailure() {
        preparePrivilegeUiExternalShellScript(
            nativeStarterCommand = { throw IOException("Cannot resolve native starter") },
            ownerUserId = 0,
            scriptPath = "files/start.sh",
            externalFilesDirectory = { error("Storage must not be accessed") },
            onScriptFailure = { error("Native resolution failures must propagate") },
        )
    }

    private fun prepare(
        nativeStarterCommand: String = "/data/app/starter.so",
        directory: () -> File?,
    ): File? = preparePrivilegeUiExternalShellScript(
        nativeStarterCommand = { nativeStarterCommand },
        ownerUserId = 0,
        scriptPath = "files/sh/start.sh",
        externalFilesDirectory = directory,
        onScriptFailure = failures::add,
    )
}
