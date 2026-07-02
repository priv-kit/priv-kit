package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbPortSelectorTest {
    @Test
    fun explicitPortWins() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = 37099,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredPort = 37100,
        )

        assertEquals(37099, port)
    }

    @Test
    fun activeTcpPortWinsWhenTcpModeIsEnabled() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredPort = 37100,
        )

        assertEquals(PRIVILEGE_ADB_DEFAULT_TCP_PORT, port)
    }

    @Test
    fun discoveredPortIsUsedWithoutTcpMode() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            tcpMode = false,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredPort = 37100,
        )

        assertEquals(37100, port)
    }

    @Test
    fun targetTcpPortIsUsedWhenTcpModeHasNoActiveOrDiscoveredPort() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = true,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredPort = null,
        )

        assertEquals(PRIVILEGE_ADB_DEFAULT_TCP_PORT, port)
    }

    @Test(expected = PrivilegeAdbException::class)
    fun missingPortFailsWithoutTcpMode() {
        PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = false,
            targetTcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            discoveredPort = null,
        )
    }
}
