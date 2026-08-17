package priv.kit.core.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import priv.kit.core.Privilege
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeFileTest {
    @Test
    fun privilegeFactoryCreatesAnAbsoluteHandleWithoutConnecting() {
        assertEquals("/data/local/tmp", Privilege.file("/data/local/tmp").absolutePath)
    }

    @Test
    fun pathPropertiesAndResolveUsePosixPathsWithoutCanonicalizing() {
        val operations = FakeOperations()
        val file = PrivilegeFile("/data/local/tmp/example/", operations)

        assertEquals("example", file.name)
        assertEquals("/data/local/tmp", file.parent)
        assertEquals("/data/local/tmp", file.parentFile?.absolutePath)
        assertEquals("/data/local/tmp/example/../child", file.resolve("../child").absolutePath)
        assertEquals(file, PrivilegeFile("/data/local/tmp/example/", operations))
        assertEquals("/data/local/tmp/example/", file.toString())
    }

    @Test
    fun hiddenStateIsDerivedLocallyFromTheFileName() {
        val operations = FakeOperations()

        assertTrue(PrivilegeFile("/data/local/tmp/.hidden", operations).isHidden())
        assertFalse(PrivilegeFile("/data/local/tmp/visible", operations).isHidden())
        assertEquals(0, operations.queryCalls)
    }

    @Test
    fun rootHasNoParentAndRejectsInvalidPaths() {
        val operations = FakeOperations()
        val root = PrivilegeFile("/", operations)

        assertEquals("", root.name)
        assertNull(root.parent)
        assertNull(root.parentFile)
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeFile("relative/path", operations)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeFile("/bad\u0000path", operations)
        }
        assertThrows(IllegalArgumentException::class.java) {
            root.resolve("/absolute")
        }
    }

    @Test
    fun pathLengthLimitUsesUtf8Bytes() {
        val operations = FakeOperations()

        PrivilegeFile("/" + "a".repeat(4_094), operations)
        PrivilegeFile("/aa" + "\u754c".repeat(1_364), operations)
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeFile("/" + "a".repeat(4_095), operations)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeFile("/" + "\u754c".repeat(1_365), operations)
        }
    }

    @Test
    fun commonFileMethodsKeepBooleanResultsAndDestinationPath() {
        val operations = FakeOperations().apply {
            queryResult = true
            queryLongResult = 42L
            createNewFileResult = false
            mkdirResult = true
            mkdirsResult = false
            deleteResult = true
            renameResult = false
        }
        val source = PrivilegeFile("/source", operations)
        val destination = PrivilegeFile("/destination", operations)

        assertTrue(source.exists())
        assertEquals(42L, source.length())
        assertFalse(source.createNewFile())
        assertTrue(source.mkdir())
        assertFalse(source.mkdirs())
        assertTrue(source.delete())
        assertFalse(source.renameTo(destination))
        assertEquals("/source", operations.renamedSource)
        assertEquals("/destination", operations.renamedDestination)
        source.replaceAtomically(destination)
        assertEquals("/source", operations.atomicReplaceSource)
        assertEquals("/destination", operations.atomicReplaceDestination)
    }

    @Test
    fun metadataIsReturnedWithoutCaching() {
        val expected = PrivilegeFileMetadata(
            absolutePath = "/entry",
            sizeBytes = 7L,
            lastModifiedMillis = 9L,
            unixMode = 0x8000 or 0x180,
            uid = 2_000,
            gid = 2_000,
        )
        val operations = FakeOperations().apply { metadataResult = expected }
        val file = PrivilegeFile("/entry", operations)

        assertSame(expected, file.metadata())
        assertSame(expected, file.metadata())
        assertEquals(2, operations.metadataCalls)
        assertEquals("entry", expected.name)
        assertEquals(PrivilegeFileType.REGULAR_FILE, expected.type)
    }

    @Test
    fun streamsDelegateTheirTransferOptions() {
        val operations = FakeOperations()
        val file = PrivilegeFile("/entry", operations)

        assertEquals("input", file.openInputStream().bufferedReader().use { it.readText() })
        file.openOutputStream(append = true, syncOnClose = true).use { output ->
            output.write("output".toByteArray())
        }

        assertEquals("/entry", operations.openInputPath)
        assertEquals("/entry", operations.openOutputPath)
        assertTrue(operations.openOutputAppend)
        assertTrue(operations.openOutputSyncOnClose)
        assertEquals("output", operations.output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun metadataNameAndTypeAreDerivedFromTheirSourceFields() {
        val expectedTypes = listOf(
            0x8000 to PrivilegeFileType.REGULAR_FILE,
            0x4000 to PrivilegeFileType.DIRECTORY,
            0xa000 to PrivilegeFileType.SYMBOLIC_LINK,
            0x6000 to PrivilegeFileType.BLOCK_DEVICE,
            0x2000 to PrivilegeFileType.CHARACTER_DEVICE,
            0x1000 to PrivilegeFileType.FIFO,
            0xc000 to PrivilegeFileType.SOCKET,
            0x0000 to PrivilegeFileType.OTHER,
        )

        expectedTypes.forEach { (mode, expectedType) ->
            val metadata = PrivilegeFileMetadata(
                absolutePath = "/directory/entry",
                sizeBytes = 0L,
                lastModifiedMillis = 0L,
                unixMode = mode or 0x180,
                uid = 0,
                gid = 0,
            )
            assertEquals("entry", metadata.name)
            assertEquals(expectedType, metadata.type)
        }
    }

    @Test
    fun directoryEntryKeepsItsNameWhenMetadataIsUnavailable() {
        val entry = PrivilegeFileDirectoryEntry(
            absolutePath = "/directory/restricted",
            metadata = null,
        )

        assertEquals("restricted", entry.name)
        assertNull(entry.metadata)
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeFileDirectoryEntry(
                absolutePath = "/directory/first",
                metadata = PrivilegeFileMetadata(
                    absolutePath = "/directory/second",
                    sizeBytes = 0L,
                    lastModifiedMillis = 0L,
                    unixMode = 0x8000,
                    uid = 0,
                    gid = 0,
                ),
            )
        }
    }

    private class FakeOperations : PrivilegeFileOperations {
        var queryResult = false
        var queryLongResult = 0L
        var createNewFileResult = false
        var mkdirResult = false
        var mkdirsResult = false
        var deleteResult = false
        var renameResult = false
        var renamedSource: String? = null
        var renamedDestination: String? = null
        var atomicReplaceSource: String? = null
        var atomicReplaceDestination: String? = null
        var openInputPath: String? = null
        var openOutputPath: String? = null
        var openOutputAppend = false
        var openOutputSyncOnClose = false
        val output = ByteArrayOutputStream()
        var queryCalls = 0
        var metadataCalls = 0
        lateinit var metadataResult: PrivilegeFileMetadata

        override fun query(path: String, kind: Int): Boolean {
            queryCalls += 1
            return queryResult
        }

        override fun queryLong(path: String, kind: Int): Long = queryLongResult

        override fun metadata(
            path: String,
            followSymbolicLinks: Boolean,
        ): PrivilegeFileMetadata {
            metadataCalls += 1
            return metadataResult
        }

        override fun openInputStream(path: String): InputStream {
            openInputPath = path
            return ByteArrayInputStream("input".toByteArray())
        }

        override fun openOutputStream(
            path: String,
            append: Boolean,
            syncOnClose: Boolean,
        ): OutputStream {
            openOutputPath = path
            openOutputAppend = append
            openOutputSyncOnClose = syncOnClose
            return output
        }

        override fun createNewFile(path: String): Boolean = createNewFileResult

        override fun mkdir(path: String): Boolean = mkdirResult

        override fun mkdirs(path: String): Boolean = mkdirsResult

        override fun delete(path: String): Boolean = deleteResult

        override fun renameTo(sourcePath: String, targetPath: String): Boolean {
            renamedSource = sourcePath
            renamedDestination = targetPath
            return renameResult
        }

        override fun replaceAtomically(sourcePath: String, targetPath: String) {
            atomicReplaceSource = sourcePath
            atomicReplaceDestination = targetPath
        }

        override fun scanDirectory(path: String): Flow<PrivilegeFileDirectoryEntry> = emptyFlow()
    }
}
