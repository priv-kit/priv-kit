package priv.kit.adb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbFileKeyStoreTest {
    @Test
    fun missingKeyFileReturnsNull() = withTemporaryDirectory { directory ->
        val keyFile = File(directory, ".priv-kit/adbkey")
        val keyStore = PrivilegeAdbFileKeyStore(keyFile)

        assertNull(keyStore.get())
    }

    @Test
    fun writesBinaryKeyFileUnderPrivKitDirectory() = withTemporaryDirectory { directory ->
        val keyFile = File(directory, ".priv-kit/adbkey")
        val keyStore = PrivilegeAdbFileKeyStore(keyFile)
        val bytes = byteArrayOf(0x00, 0x01, 0x2a, (-1).toByte())

        keyStore.put(bytes)

        assertTrue(keyFile.isFile)
        assertArrayEquals(bytes, keyFile.readBytes())
        assertArrayEquals(bytes, keyStore.get())
    }

    @Test
    fun replacesExistingKeyFile() = withTemporaryDirectory { directory ->
        val keyFile = File(directory, ".priv-kit/adbkey")
        val keyStore = PrivilegeAdbFileKeyStore(keyFile)

        keyStore.put(byteArrayOf(0x01))
        keyStore.put(byteArrayOf(0x02, 0x03))

        assertArrayEquals(byteArrayOf(0x02, 0x03), keyStore.get())
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("priv-adb-key-store").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
