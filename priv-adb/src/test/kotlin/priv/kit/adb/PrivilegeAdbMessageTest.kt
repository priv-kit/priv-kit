package priv.kit.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbMessageTest {
    @Test
    fun roundTripStringPayload() {
        val message = PrivilegeAdbMessage(
            command = PrivilegeAdbProtocol.A_OPEN,
            arg0 = 1,
            arg1 = 0,
            data = "shell:id",
        )

        val parsed = PrivilegeAdbMessage.fromByteArray(message.toByteArray())

        assertEquals(message.command, parsed.command)
        assertEquals(message.arg0, parsed.arg0)
        assertEquals(message.arg1, parsed.arg1)
        assertArrayEquals("shell:id\u0000".toByteArray(), parsed.data)
        assertTrue(parsed.validate())
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
