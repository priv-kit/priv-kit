package priv.kit.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrivilegeAdbMessageTest {
    @Test
    fun roundTripStringPayload() {
        val message = PrivilegeAdbMessage(
            command = PrivilegeAdbProtocol.A_OPEN,
            arg0 = 1,
            arg1 = 0,
            data = "shell:id",
        )

        val buffer = ByteBuffer.wrap(message.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(message.command, buffer.int)
        assertEquals(message.arg0, buffer.int)
        assertEquals(message.arg1, buffer.int)
        assertEquals(message.dataLength, buffer.int)
        assertEquals(message.dataCrc32, buffer.int)
        assertEquals(message.magic, buffer.int)
        assertArrayEquals(
            "shell:id\u0000".toByteArray(),
            ByteArray(message.dataLength).also { buffer.get(it) },
        )
        assertTrue(message.validate())
    }

    @Test
    fun detectsBadMagic() {
        val message = PrivilegeAdbMessage(
            command = PrivilegeAdbProtocol.A_OPEN,
            arg0 = 1,
            arg1 = 0,
            data = "shell:id",
        )
        val invalid = PrivilegeAdbMessage(
            command = message.command,
            arg0 = message.arg0,
            arg1 = message.arg1,
            dataLength = message.dataLength,
            dataCrc32 = message.dataCrc32,
            magic = 0,
            data = message.data,
        )

        assertFalse(invalid.validate())
    }
}
