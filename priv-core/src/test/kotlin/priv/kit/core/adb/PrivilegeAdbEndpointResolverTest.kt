package priv.kit.core.adb

import java.io.IOException

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeStartupException
import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbEndpointResolverTest {
    @Test
    fun deniedManagementHonorsControlPolicyAndDoesNotCleanUpUnmanagedDebugging() = runBlocking {
        for (discoveryFailure in listOf(null, IOException("No endpoint"), CancellationException("cancelled"))) {
            for (mode in PrivilegeAdbWirelessDebuggingControl.entries) {
                var preparations = 0
                var discoveries = 0
                val denied = SecurityException("android.permission.MANAGE_DEBUGGING")
                val controller = object : PrivilegeAdbWirelessDebuggingController {
                    override fun status() = PrivilegeAdbWirelessDebuggingControlStatus(
                        true, true, true, false, true,
                    )
                    override fun enableAdb() = Unit
                    override fun prepareAdb() {
                        preparations++
                        throw IOException("System denied global setting adb_enabled=1", denied)
                    }
                    override fun setWirelessDebuggingEnabled(enabled: Boolean) = error("Unexpected write")
                }
                val resolver = PrivilegeAdbEndpointResolver(
                    nsdManagerProvider = { error("unused") },
                    wirelessDebuggingControllerProvider = { controller },
                    connectEndpointDiscovery = {
                        discoveries++
                        discoveryFailure?.let { throw it }
                        PrivilegeAdbEndpoint.local(PRIVILEGE_ADB_DEFAULT_TCP_PORT)
                    },
                )
                val result = runCatching {
                    resolver.acquireConnectEndpointForStart(
                        PrivilegeAdbConnectionOptions(wirelessDebuggingControl = mode),
                        PrivilegeAdbOutput(),
                        true,
                    ).close()
                }
                assertEquals(if (mode == PrivilegeAdbWirelessDebuggingControl.NEVER) 0 else 1, preparations)
                if (mode == PrivilegeAdbWirelessDebuggingControl.REQUIRE) {
                    assertEquals(0, discoveries)
                    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Developer options"))
                    assertTrue(result.exceptionOrNull()?.cause?.cause === denied)
                } else {
                    assertEquals(1, discoveries)
                    when {
                        discoveryFailure == null -> result.getOrThrow()
                        discoveryFailure is CancellationException ->
                            assertTrue(result.exceptionOrNull() === discoveryFailure)
                        mode == PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE -> {
                            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Developer options"))
                            assertTrue(result.exceptionOrNull()?.cause?.cause === denied)
                            assertEquals(1, result.exceptionOrNull()?.suppressed?.size)
                        }
                        else -> assertTrue(result.isFailure)
                    }
                }
            }
        }
    }

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
        assertTrue(output.text().contains("Wireless debugging disable request submitted"))
    }

    @Test
    fun cleanupFailureIsDiagnosticAndDoesNotThrowOrReportSuccess() {
        val output = PrivilegeAdbOutput()
        val controller = object : PrivilegeAdbWirelessDebuggingController {
            override fun status(): PrivilegeAdbWirelessDebuggingControlStatus = error("unused")
            override fun enableAdb() = error("unused")
            override fun prepareAdb() = error("unused")
            override fun setWirelessDebuggingEnabled(enabled: Boolean) {
                assertFalse(enabled)
                throw IOException("Failed to write global setting adb_wifi_enabled=0")
            }
        }
        val lease = PrivilegeAdbConnectEndpointLease(
            endpoint = PrivilegeAdbEndpoint.local(PRIVILEGE_ADB_DEFAULT_TCP_PORT),
            cleanupController = controller,
            output = output,
        )

        lease.close()

        assertTrue(output.text().contains("Failed to disable Wireless debugging"))
        assertTrue(output.text().contains("adb_wifi_enabled=0"))
        assertFalse(output.text().contains("Wireless debugging disable request submitted"))
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
