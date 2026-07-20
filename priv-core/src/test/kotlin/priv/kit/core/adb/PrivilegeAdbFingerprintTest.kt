package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbFingerprintTest {
    @Test
    fun fingerprintUsesAndroidAdbDialogMd5Format() {
        assertEquals(
            "96:00:3A:81:12:F7:B1:08:6B:1D:5F:F0:76:C7:0A:6E",
            "priv-kit-adb-public-key".toByteArray().adbDialogFingerprint(),
        )
    }
}
