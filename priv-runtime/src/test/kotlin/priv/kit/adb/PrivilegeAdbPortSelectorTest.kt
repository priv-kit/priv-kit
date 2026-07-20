package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.PrivilegeStartupException
import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST

class PrivilegeAdbPortSelectorTest {
    @Test
    fun explicitPortWins() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = 37099,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = PrivilegeAdbEndpoint.local(37100),
        )

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint.host)
        assertEquals(37099, endpoint.port)
    }

    @Test
    fun activeTcpPortWinsWhenTcpModeIsEnabled() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = PrivilegeAdbEndpoint.local(37100),
        )

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint.host)
        assertEquals(PRIVILEGE_ADB_DEFAULT_TCP_PORT, endpoint.port)
    }

    @Test
    fun discoveredPortIsUsedWithoutTcpMode() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = false,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = PrivilegeAdbEndpoint.local(37100),
        )

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint.host)
        assertEquals(37100, endpoint.port)
    }

    @Test
    fun discoveredEndpointPreservesResolvedHost() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = false,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = PrivilegeAdbEndpoint("192.168.1.12", 37100),
        )

        assertEquals("192.168.1.12", endpoint.host)
        assertEquals(37100, endpoint.port)
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

    @Test
    fun targetTcpPortIsUsedWhenTcpModeHasNoActiveOrDiscoveredPort() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = null,
        )

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint.host)
        assertEquals(PRIVILEGE_ADB_DEFAULT_TCP_PORT, endpoint.port)
    }

    @Test
    fun discoveredPortIsUsedBeforeTargetTcpPortWhenTcpModeIsEnabled() {
        val endpoint = PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = PrivilegeAdbEndpoint.local(37100),
        )

        assertEquals(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, endpoint.host)
        assertEquals(37100, endpoint.port)
    }

    @Test(expected = PrivilegeAdbException::class)
    fun missingPortFailsWithoutTcpMode() {
        PrivilegeAdbPortSelector.chooseStartEndpoint(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = false,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredEndpoint = null,
        )
    }
}
