package priv.kit.core.internal.file

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeFileWireTest {
    @Test
    fun nameOnlyWalkEntryRoundTripsWithDepthAndWithoutMetadata() {
        val bytes = ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { output ->
                PrivilegeFileWire.writeEntry(output, "/cache", depth = 3, stat = null)
            }
        }.toByteArray()

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(PrivilegeFileSystemContract.WALK_ENTRY, input.readUnsignedByte())
            val entry = PrivilegeFileWire.readEntry(input)
            assertEquals("/cache", entry.absolutePath)
            assertEquals("cache", entry.name)
            assertEquals(3, entry.depth)
            assertNull(entry.metadata)
        }
    }
}
