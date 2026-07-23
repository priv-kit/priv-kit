package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbEndpointResolverTest {
    @Test
    fun connectEndpointLeaseRejectsRepeatedCloseWithoutRepeatingCleanup() {
        var disableCalls = 0
        val output = PrivilegeAdbOutput()
        val controller = object : PrivilegeAdbWirelessDebuggingController {
            override fun status(): PrivilegeAdbWirelessDebuggingControlStatus =
                PrivilegeAdbWirelessDebuggingControlStatus(
                    supported = true,
                    permissionDeclared = false,
                    permissionGranted = false,
                    wirelessDebuggingEnabled = false,
                    canManage = false,
                )

            override fun enableAdb() = Unit

            override fun prepareAdb() = Unit

            override fun setWirelessDebuggingEnabled(enabled: Boolean) {
                if (!enabled) disableCalls += 1
            }
        }
        val lease = PrivilegeAdbConnectEndpointLease(
            endpoint = PrivilegeAdbEndpoint.local(PRIVILEGE_ADB_DEFAULT_TCP_PORT),
            cleanupController = controller,
            output = output,
        )

        lease.close()
        val exception = assertThrows(IllegalStateException::class.java) {
            lease.close()
        }

        assertEquals(1, disableCalls)
        assertTrue(exception.message.orEmpty().contains("already closed"))
        assertTrue(output.text().contains("Wireless debugging disabled"))
    }

    @Test
    fun managedWirelessDebuggingAddsLimitedConnectPortDiscoveryRetries() {
        assertEquals(
            1,
            managedWirelessConnectPortDiscoveryAttempts(
                managedWirelessDebuggingEnabled = false,
                connectRetryCount = 5,
            ),
        )
        assertEquals(
            3,
            managedWirelessConnectPortDiscoveryAttempts(
                managedWirelessDebuggingEnabled = true,
                connectRetryCount = 5,
            ),
        )
        assertEquals(
            1,
            managedWirelessConnectPortDiscoveryAttempts(
                managedWirelessDebuggingEnabled = true,
                connectRetryCount = 1,
            ),
        )
    }
}
