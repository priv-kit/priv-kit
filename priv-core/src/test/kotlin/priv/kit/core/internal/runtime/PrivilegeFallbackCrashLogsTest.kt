package priv.kit.core.internal.runtime

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivilegeFallbackCrashLogsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scansReportsWithoutAnIndexAndTreatsMissingDirectoryAsEmpty() {
        val directory = temporaryFolder.root.toPath()
        val report = temporaryFolder.newFile("priv-crash_uid2000_20261005203357.json")
        temporaryFolder.newFile("${report.name}.tmp")
        temporaryFolder.newFolder("priv-crash_uid0_20261005203357.json")

        assertEquals(listOf(report), PrivilegeFallbackCrashLogs.listFiles(directory))
        assertTrue(PrivilegeFallbackCrashLogs.listFiles(directory.resolve("missing")).isEmpty())
        assertTrue(PrivilegeFallbackCrashLogs.listFiles(temporaryFolder.newFolder("empty").toPath()).isEmpty())
    }

    @Test
    fun fallsBackToIndexWhenOpeningOrIteratingDirectoryFails() {
        val directory = temporaryFolder.root.toPath()
        val report = temporaryFolder.newFile("priv-crash_uid2000_20261005203357.json")
        val temporary = temporaryFolder.newFile("${report.name}.tmp")
        directory.resolve(PrivilegeFallbackCrashLogs.INDEX_NAME).toFile().writeText(
            listOf(report.name, "../${report.name}", temporary.name, report.name, "priv-crash_uid0_20261005203357.json")
                .joinToString("\n"),
        )
        val denied = AccessDeniedException(directory.toString())
        val failedIteration = object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = throw DirectoryIteratorException(denied)
            override fun close() = Unit
        }

        assertEquals(listOf(report), PrivilegeFallbackCrashLogs.listFiles(directory) { throw denied })
        assertEquals(listOf(report), PrivilegeFallbackCrashLogs.listFiles(directory) { failedIteration })
    }

    @Test
    fun missingIndexDoesNotTurnListingFailureIntoEmptyResult() {
        val directory = temporaryFolder.root.toPath()
        val denied = AccessDeniedException(directory.toString())
        val failure = assertThrows(AccessDeniedException::class.java) {
            PrivilegeFallbackCrashLogs.listFiles(directory) { throw denied }
        }

        assertSame(denied, failure)
        assertTrue(failure.suppressed.single() is NoSuchFileException)
    }
}
