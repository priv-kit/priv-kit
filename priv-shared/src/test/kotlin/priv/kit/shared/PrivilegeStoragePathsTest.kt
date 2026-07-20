package priv.kit.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class PrivilegeStoragePathsTest {
    @Test
    public fun createsPathUnderPrivKitDirectory() {
        val filesDir = File("root/files")

        assertEquals(
            File(filesDir, ".priv-kit/ui-start-method"),
            PrivilegeStoragePaths.file(filesDir, "ui-start-method"),
        )
    }

    @Test
    public fun rejectsBlankFileName() {
        listOf("", " ", "\t\r\n").forEach { fileName ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivilegeStoragePaths.file(File("root/files"), fileName)
            }
        }
    }
}
