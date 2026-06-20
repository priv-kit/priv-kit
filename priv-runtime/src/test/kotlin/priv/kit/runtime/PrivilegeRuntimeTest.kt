package priv.kit.runtime

import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.binder.PrivilegeServerDisconnectedException

class PrivilegeRuntimeTest {
    @Test
    fun getServerInfoWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            PrivilegeRuntime.getServerInfo()
        }
    }

    @Test
    fun requireBinderEndpointWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            PrivilegeRuntime.requireBinderEndpoint()
        }
    }
}
