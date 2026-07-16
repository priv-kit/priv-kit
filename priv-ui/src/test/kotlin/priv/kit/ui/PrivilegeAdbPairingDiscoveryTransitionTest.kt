package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import priv.kit.ui.adb.pairing.privilegeAdbPairingDiscoveryTransition

class PrivilegeAdbPairingDiscoveryTransitionTest {
    @Test
    fun pairingServicePresenceTransitionsBackToSearchingAfterPortDisappears() {
        assertNull(privilegeAdbPairingDiscoveryTransition(previousPort = null, observedPort = null))
        assertEquals(
            PrivilegeUiAdbPairingStatus.FOUND,
            privilegeAdbPairingDiscoveryTransition(previousPort = null, observedPort = 37_123),
        )
        assertNull(privilegeAdbPairingDiscoveryTransition(previousPort = 37_123, observedPort = 37_123))
        assertEquals(
            PrivilegeUiAdbPairingStatus.SEARCHING,
            privilegeAdbPairingDiscoveryTransition(previousPort = 37_123, observedPort = null),
        )
    }
}
