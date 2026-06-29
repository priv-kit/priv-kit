package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbPortSelectorTest {
    @Test
    fun explicitPortWins() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = 37099,
            activeTcpPort = 5555,
            tcpMode = true,
            targetTcpPort = 5555,
            discoveredPort = 37100,
        )

        assertEquals(37099, port)
    }

    @Test
    fun activeTcpPortWinsWhenTcpModeIsEnabled() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = 5555,
            tcpMode = true,
            targetTcpPort = 5555,
            discoveredPort = 37100,
        )

        assertEquals(5555, port)
    }

    @Test
    fun discoveredPortIsUsedWithoutTcpMode() {
        val port = PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = 5555,
            tcpMode = false,
            targetTcpPort = 5555,
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
            targetTcpPort = 5555,
            discoveredPort = null,
        )

        assertEquals(5555, port)
    }

    @Test(expected = PrivilegeAdbException::class)
    fun missingPortFailsWithoutTcpMode() {
        PrivilegeAdbPortSelector.chooseStartPort(
            explicitPort = null,
            activeTcpPort = -1,
            tcpMode = false,
            targetTcpPort = 5555,
            discoveredPort = null,
        )
    }
}
