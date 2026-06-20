package priv.kit.binder

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.core.PrivilegeProtocol

class PrivilegeBinderProtocolTest {
    @Test
    fun serverClasspathIdentityProtocolRequiresVersion7() {
        assertEquals(7, PrivilegeProtocol.VERSION)
    }
}
