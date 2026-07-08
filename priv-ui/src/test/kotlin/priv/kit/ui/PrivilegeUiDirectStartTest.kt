package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                tcpModeEnabled = false,
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
                tcpModeEnabled = false,
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
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
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
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun wirelessAdbDirectStartBlocksKnownUnpairedDevice() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
            wifiConnected = true,
        )

        assertNull(
            state.directStartTarget(
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun externalStartTargetRequiresReadyProvider() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.EXTERNAL,
            startupModes = listOf(
                PrivilegeUiStartupMode.EXTERNAL,
                PrivilegeUiStartupMode.ROOT,
            ),
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
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun rootFallsBackWhenEarlierModesCannotStartDirectly() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
                PrivilegeUiStartupMode.ROOT,
            ),
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Root,
            state.directStartTarget(
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun selectedRootKeepsReadyAdbAsFallbackTarget() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ROOT,
            startupModes = listOf(
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.ROOT,
            ),
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
            wifiConnected = true,
        )

        assertEquals(
            listOf(
                PrivilegeUiDirectStartTarget.Root,
                PrivilegeUiDirectStartTarget.Adb,
            ),
            state.directStartTargets(
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun manualOnlyHasNoDirectStartFallbackTargets() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.MANUAL_SHELL,
            startupModes = listOf(PrivilegeUiStartupMode.MANUAL_SHELL),
        )

        assertFalse(
            state.hasDirectStartTarget(
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )
    }

    @Test
    fun wirelessAdbDirectStartRequiresWifi() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
            wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
            wifiConnected = false,
        )

        assertNull(
            state.directStartTarget(
                tcpModeEnabled = false,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }

    @Test
    fun staticTcpDirectStartDoesNotRequireWifi() {
        val state = PrivilegeUiState(
            selectedStartupMode = PrivilegeUiStartupMode.ADB,
            startupModes = listOf(PrivilegeUiStartupMode.ADB),
            tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            wifiConnected = false,
        )

        assertEquals(
            PrivilegeUiDirectStartTarget.Adb,
            state.directStartTarget(
                tcpModeEnabled = true,
                tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
        )
    }
}
