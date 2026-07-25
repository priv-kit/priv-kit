package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeStartupException
import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST

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

    @Test
    fun resolvedEndpointPrefersLoopbackWhenSamePortListens() {
        val endpoint = privilegeAdbReachableLocalEndpoint(
            serviceHost = "192.168.1.12",
            port = 37100,
        ) { host, port ->
            host == PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST && port == 37100
        }

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint?.host)
        assertEquals(37100, endpoint?.port)
    }

    @Test
    fun resolvedEndpointFallsBackToServiceHostWhenLoopbackIsUnavailable() {
        val endpoint = privilegeAdbReachableLocalEndpoint(
            serviceHost = "192.168.1.12",
            port = 37100,
        ) { host, port ->
            host == "192.168.1.12" && port == 37100
        }

        assertEquals("192.168.1.12", endpoint?.host)
        assertEquals(37100, endpoint?.port)
    }

    @Test
    fun localNetworkAccessFailureIsDetectedThroughCauseChain() {
        val throwable = PrivilegeStartupException(
            message = "failed",
            cause = PrivilegeAdbLocalNetworkAccessException(
                endpoint = PrivilegeAdbEndpoint("192.168.1.12", 37100),
                cause = IllegalStateException("blocked"),
            ),
        )

        assertTrue(throwable.isPrivilegeAdbLocalNetworkAccessFailure())
    }

    @Test
    fun localNetworkAccessFailureDoesNotMatchPlainFailure() {
        assertFalse(IllegalStateException("blocked").isPrivilegeAdbLocalNetworkAccessFailure())
    }
}
