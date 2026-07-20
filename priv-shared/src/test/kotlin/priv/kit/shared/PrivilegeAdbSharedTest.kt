package priv.kit.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PrivilegeAdbSharedTest {
    @Test
    public fun validatesAdbPortBoundaries() {
        assertFalse(0.isPrivilegeAdbPort())
        assertTrue(1.isPrivilegeAdbPort())
        assertTrue(PRIVILEGE_INTERNAL_DEFAULT_ADB_TCP_PORT.isPrivilegeAdbPort())
        assertTrue(65_535.isPrivilegeAdbPort())
        assertFalse(65_536.isPrivilegeAdbPort())
    }

    @Test
    public fun extractsAndValidatesAsciiPairingCodeDigits() {
        assertEquals(
            "123456",
            "Pairing code: 123 456 789".toPrivilegeAdbPairingCodeDigits(),
        )
        assertTrue("123456".isPrivilegeAdbPairingCode())
        assertFalse("12345".isPrivilegeAdbPairingCode())
        assertFalse("1234567".isPrivilegeAdbPairingCode())
        assertFalse("١٢٣٤٥٦".isPrivilegeAdbPairingCode())
        assertEquals("", "١٢٣٤٥٦".toPrivilegeAdbPairingCodeDigits())
    }

    @Test
    public fun sanitizesControlLineCharactersAndOuterWhitespace() {
        assertEquals(
            "adb device name",
            " \tadb\u0000device\rname\n ".toPrivilegeAdbDeviceNameText(),
        )
    }

    @Test
    public fun limitsSanitizedDeviceNameToMaximumLength() {
        val text = "x".repeat(PRIVILEGE_INTERNAL_MAX_ADB_DEVICE_NAME_LENGTH + 20)

        assertEquals(
            "x".repeat(PRIVILEGE_INTERNAL_MAX_ADB_DEVICE_NAME_LENGTH),
            text.toPrivilegeAdbDeviceNameText(),
        )
    }

    @Test
    public fun recognizesAdbKeyMessageThroughCauseChain() {
        val failure = IllegalStateException(
            "startup failed",
            IllegalArgumentException("transport: ADB key is not authorized"),
        )

        assertTrue(failure.hasPrivilegeAdbKeyNotAuthorizedMessage())
        assertFalse(IllegalStateException("adb key is not authorized").hasPrivilegeAdbKeyNotAuthorizedMessage())
        assertFalse(IllegalStateException("unrelated").hasPrivilegeAdbKeyNotAuthorizedMessage())
    }

    @Test
    public fun recognizesBothCertificateUnknownSpellingsIgnoringCase() {
        assertTrue("ssl alert CERTIFICATE_UNKNOWN".hasPrivilegeAdbCertificateUnknownMessage())
        assertTrue("tls alert Certificate Unknown".hasPrivilegeAdbCertificateUnknownMessage())
        assertFalse("certificate expired".hasPrivilegeAdbCertificateUnknownMessage())
    }
}
