package priv.kit.core.adb

import javax.net.ssl.SSLProtocolException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbFailureMessagesTest {
    @Test
    fun certificateUnknownTextRequiresSslProtocolException() {
        assertFalse(
            IllegalStateException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")
                .isAdbKeyNotAuthorized(),
        )
    }

    @Test
    fun certificateUnknownSslProtocolExceptionIsNotAuthorized() {
        assertTrue(
            SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")
                .isAdbKeyNotAuthorized(),
        )
    }
}
