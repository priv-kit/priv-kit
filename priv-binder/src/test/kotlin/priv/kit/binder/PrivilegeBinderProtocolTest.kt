package priv.kit.binder

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.core.PrivilegeProtocol

class PrivilegeBinderProtocolTest {
    @Test
    fun userServiceContextProtocolRequiresVersion8() {
        assertEquals(8, PrivilegeProtocol.VERSION)
    }
}
