package priv.kit.binder

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.internal.core.PrivilegeProtocol

class PrivilegeBinderProtocolTest {
    @Test
    fun remoteSystemServiceBinderProtocolRequiresVersion9() {
        assertEquals(9, PrivilegeProtocol.VERSION)
    }
}
