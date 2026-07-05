package priv.kit.binder

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.internal.core.PrivilegeProtocol

class PrivilegeBinderProtocolTest {
    @Test
    fun remoteSystemServiceBinderProtocolRequiresVersion12() {
        assertEquals(12, PrivilegeProtocol.VERSION)
    }
}
