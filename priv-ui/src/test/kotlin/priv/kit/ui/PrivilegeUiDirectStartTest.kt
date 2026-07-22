package priv.kit.ui

import priv.kit.ui.runtime.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeUiDirectStartTest {
    @Test
    fun manualOnlyHasNoDirectStartTarget() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.MANUAL_SHELL,
            startupModes = listOf(PrivilegeUiStartupMode.MANUAL_SHELL),
        )

        assertNull(
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun selectedAdbStartsWhenPaired() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.ROOT,
            ),
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
            wifiConnected = true,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun wirelessAdbDirectStartRequiresSupportedPlatform() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
        )

        assertNull(
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                wirelessAdbSupported = false,
            ),
        )
    }

    @Test
    fun selectedAdbStartsWhenManagedWirelessAdbIsReady() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
            wifiConnected = true,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun wirelessAdbAttemptIsEnumeratedDespiteStaleUnpairedState() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
            wifiConnected = true,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun externalStartTargetRequiresReadyProvider() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.EXTERNAL,
            startupModes = listOf(PrivilegeUiStartupMode.EXTERNAL),
            externalStartItems = listOf(
                PrivilegeUiExternalStartItemState(
                    id = "provider",
                    label = "Provider",
                    snapshot = PrivilegeUiExternalStartSnapshot(
                        available = true,
                        authorized = true,
                    ),
                ),
            ),
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.External("provider"),
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun selectedAdbAttemptPrecedesRootDespiteUnknownCachedState() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
                PrivilegeUiStartupMode.ROOT,
            ),
        )

        assertEquals(
            listOf(
                PrivilegeUiDirectStartTarget.Adb,
                PrivilegeUiDirectStartTarget.Root,
            ),
            state.directStartTargets(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun selectedRootKeepsStaticAdbFallbackWithoutCachedReadiness() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ROOT,
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.ROOT,
            ),
            tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            wifiConnected = false,
        )

        assertEquals(
            listOf(
                PrivilegeUiDirectStartTarget.Root,
                PrivilegeUiDirectStartTarget.Adb,
            ),
            state.directStartTargets(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun wirelessAdbAttemptIsEnumeratedWithoutCachedWifi() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
            wifiConnected = false,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun staticTcpDirectStartDoesNotRequireCachedPortOrAuthorization() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            wifiConnected = false,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }
}

private fun PrivilegeUiState.directStartTarget(
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    wirelessAdbSupported: Boolean = true,
): PrivilegeUiDirectStartTarget? =
    directStartTargets(
        tcpPolicy = tcpPolicy,
        wirelessAdbSupported = wirelessAdbSupported,
    ).firstOrNull()
