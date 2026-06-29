package priv.kit.internal.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeBinaryFileStoreTest {
    @Test
    fun missingFileReturnsNull() = withTemporaryDirectory { directory ->
        val file = File(directory, ".priv-kit/adbkey")

        assertNull(PrivilegeBinaryFileStore.readIfExists(file))
    }

    @Test
    fun writesBinaryFileUnderPrivKitDirectory() = withTemporaryDirectory { directory ->
        val file = File(directory, ".priv-kit/adbkey")
        val bytes = byteArrayOf(0x00, 0x01, 0x2a, (-1).toByte())

        PrivilegeBinaryFileStore.writeAtomically(file, bytes)

        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(bytes, PrivilegeBinaryFileStore.readIfExists(file))
    }

    @Test
    fun replacesExistingFile() = withTemporaryDirectory { directory ->
        val file = File(directory, ".priv-kit/adbkey")

        PrivilegeBinaryFileStore.writeAtomically(file, byteArrayOf(0x01))
        PrivilegeBinaryFileStore.writeAtomically(file, byteArrayOf(0x02, 0x03))

        assertArrayEquals(byteArrayOf(0x02, 0x03), PrivilegeBinaryFileStore.read(file))
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("priv-binary-file-store").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
