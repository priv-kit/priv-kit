package priv.kit.binder

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.core.PrivilegeProtocol

class PrivilegeBinderProtocolTest {
    @Test
    fun launchModeProtocolRequiresVersion5() {
        assertEquals(5, PrivilegeProtocol.VERSION)
    }
}
