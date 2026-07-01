package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.PrivilegeStartupLogListener

class PrivilegeAdbOutputTest {
    @Test
    fun appendStreamsEachOutputLineToListener() {
        val received = mutableListOf<String>()
        val output = PrivilegeAdbOutput(
            PrivilegeStartupLogListener { line ->
                received += "[${line.source}] ${line.message}"
            },
        )

        output.append("adb", "first\nsecond\r\n")

        assertEquals(
            listOf("[adb] first", "[adb] second"),
            received,
        )
        assertEquals(
            "[adb] first\n[adb] second",
            output.text(),
        )
    }
}
