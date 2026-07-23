package priv.kit.shared

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class PrivilegeBinaryFileStoreTest {
    @Test
    public fun missingFileReturnsNull() {
        withTemporaryDirectory { directory ->
            val file = File(directory, ".priv-kit/adbkey")

            assertNull(PrivilegeBinaryFileStore.readIfExists(file))
        }
    }

    @Test
    public fun writesAndReadsRawBytes() {
        withTemporaryDirectory { directory ->
            val file = File(directory, ".priv-kit/adbkey")
            val bytes = byteArrayOf(0x00, 0x01, 0x2a, 0x7f, 0x80.toByte(), 0xff.toByte())

            PrivilegeBinaryFileStore.writeAtomically(file, bytes)

            assertTrue(file.isFile)
            assertArrayEquals(bytes, file.readBytes())
            assertArrayEquals(bytes, PrivilegeBinaryFileStore.readIfExists(file))
            assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        }
    }

    @Test
    public fun atomicallyReplacesExistingFile() {
        withTemporaryDirectory { directory ->
            val file = File(directory, ".priv-kit/adbkey")

            PrivilegeBinaryFileStore.writeAtomically(file, byteArrayOf(0x01))
            PrivilegeBinaryFileStore.writeAtomically(file, byteArrayOf(0x02, 0x03))

            assertArrayEquals(byteArrayOf(0x02, 0x03), file.readBytes())
            assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        }
    }

    @Test
    public fun doesNotConsumeAnotherWritersFixedTemporaryFile() {
        withTemporaryDirectory { directory ->
            val file = File(directory, ".priv-kit/adbkey")
            val fixedTemporaryFile = File(file.parentFile, "${file.name}.tmp")
            val unrelatedBytes = byteArrayOf(0x01, 0x02)
            val targetBytes = byteArrayOf(0x03, 0x04)
            assertTrue(requireNotNull(fixedTemporaryFile.parentFile).mkdirs())
            fixedTemporaryFile.writeBytes(unrelatedBytes)

            PrivilegeBinaryFileStore.writeAtomically(file, targetBytes)

            assertArrayEquals(targetBytes, file.readBytes())
            assertArrayEquals(unrelatedBytes, fixedTemporaryFile.readBytes())
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("priv-shared-binary-file-store").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
