package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbPairingCodeTest {
    @Test
    fun extractsDigitsFromPairingCodeText() {
        assertEquals("123456", "Pairing code: 123 456".toPrivilegeAdbPairingCode())
    }

    @Test
    fun limitsPairingCodeToSixDigits() {
        assertEquals("123456", "123-456-789".toPrivilegeAdbPairingCode())
    }

    @Test
    fun returnsBlankWhenNoDigitsExist() {
        assertEquals("", "pairing failed".toPrivilegeAdbPairingCode())
    }

    @Test
    fun rejectsIncompleteOrNonAsciiCodes() {
        assertEquals("", "Pairing code: 12345".toPrivilegeAdbPairingCode())
        assertEquals("", "Pairing code: ١٢٣٤٥٦".toPrivilegeAdbPairingCode())
    }
}
