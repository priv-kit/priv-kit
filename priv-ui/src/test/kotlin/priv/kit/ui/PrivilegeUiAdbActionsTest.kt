package priv.kit.ui

import android.os.Build
import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.adb.PrivilegeAdbAuthorizationStatus

class PrivilegeUiAdbActionsTest {
    @Test
    fun pairingCheckStatusUsesWirelessAndRefreshResult() {
        listOf(
            PairingCheckCase(
                wirelessDebuggingOn = false,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = true,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbStatus.ON,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = false,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.OFF,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.ON,
            ),
            PairingCheckCase(
                wirelessDebuggingOn = true,
                pairingCheckPaired = null,
                currentStatus = PrivilegeUiWirelessAdbStatus.CHECKING,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                privilegeUiPairingCheckStatus(
                    wirelessDebuggingOn = case.wirelessDebuggingOn,
                    pairingCheckPaired = case.pairingCheckPaired,
                    currentStatus = case.currentStatus,
                ),
            )
        }
    }

    @Test
    fun refreshingPairingCheckStatusReflectsWirelessState() {
        listOf(
            RefreshingPairingCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                currentStatus = PrivilegeUiWirelessAdbStatus.ON,
                expected = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            ),
            RefreshingPairingCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                currentStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                expected = PrivilegeUiWirelessAdbStatus.CHECKING,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                privilegeUiRefreshingPairingCheckStatus(
                    wirelessDebuggingStatus = case.wirelessDebuggingStatus,
                    currentStatus = case.currentStatus,
                ),
            )
        }
    }

    @Test
    fun wirelessAdbStartRequiresPairingOnlyWhenKnownUnpaired() {
        assertEquals(
            true,
            shouldRequireWirelessPairingForStart(PrivilegeUiWirelessAdbStatus.OFF),
        )
        assertEquals(
            false,
            shouldRequireWirelessPairingForStart(PrivilegeUiWirelessAdbStatus.UNKNOWN),
        )
        assertEquals(
            false,
            shouldRequireWirelessPairingForStart(PrivilegeUiWirelessAdbStatus.ON),
        )
    }

    @Test
    fun wirelessDebuggingStatusUsesSettingInsteadOfDiscoveredServices() {
        assertEquals(
            PrivilegeUiWirelessAdbStatus.ON,
            privilegeUiWirelessDebuggingStatus(
                wirelessDebuggingEnabled = true,
                connectPortAvailable = false,
                pairingServiceOn = false,
            ),
        )
        assertEquals(
            PrivilegeUiWirelessAdbStatus.OFF,
            privilegeUiWirelessDebuggingStatus(
                wirelessDebuggingEnabled = false,
                connectPortAvailable = true,
                pairingServiceOn = true,
            ),
        )
    }

    @Test
    fun adbKeyUnauthorizedFailureIsRecognizedFromStartupExceptionChain() {
        val failure = RuntimeException(
            "Failed to start Privileged Server with ADB",
            IllegalStateException("ADB key is not authorized"),
        )

        assertTrue(failure.isAdbKeyNotAuthorizedFailure())
    }

    @Test
    fun adbTlsCertificateUnknownFailureIsRecognizedAsUnauthorized() {
        val failure = RuntimeException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")

        assertTrue(failure.isAdbKeyNotAuthorizedFailure())
    }

    @Test
    fun tcpAuthorizationPollingLogsFailureOnlyWhenStatusChanges() {
        assertTrue(
            shouldAppendTcpAuthorizationFailureLog(
                previousStatus = PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
                nextStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                failureMessage = "ConnectException: refused",
            ),
        )
        assertFalse(
            shouldAppendTcpAuthorizationFailureLog(
                previousStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                nextStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                failureMessage = "ConnectException: refused",
            ),
        )
        assertFalse(
            shouldAppendTcpAuthorizationFailureLog(
                previousStatus = PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
                nextStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                failureMessage = null,
            ),
        )
    }

    @Test
    fun tcpAuthorizationRefreshPausesWhileWaitingForUserAuthorization() {
        PrivilegeUiAdbTcpAuthorizationStatus.entries.forEach { status ->
            assertEquals(
                status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                shouldSkipTcpAuthorizationRefresh(status),
            )
        }
    }

    @Test
    fun silentFallbackNeverRequestsStaticTcpAuthorization() {
        PrivilegeUiAdbTcpAuthorizationStatus.entries.forEach { status ->
            assertFalse(
                shouldRequestStaticTcpAuthorizationForStart(
                    authorizationStatus = status,
                    showAttemptFeedback = false,
                ),
            )
        }
        listOf(
            PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
            PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
        ).forEach { status ->
            assertTrue(
                shouldRequestStaticTcpAuthorizationForStart(
                    authorizationStatus = status,
                    showAttemptFeedback = true,
                ),
            )
        }
    }

    @Test
    fun wirelessAdbStartRequiresWirelessDebuggingWhenOffOrCannotBeManaged() {
        listOf(
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
                expected = false,
            ),
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.PERMISSION_REQUIRED,
                expected = true,
            ),
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.PERMISSION_REQUIRED,
                expected = true,
            ),
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNDECLARED,
                expected = true,
            ),
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.PERMISSION_REQUIRED,
                expected = false,
            ),
            WirelessDebuggingRequirementCase(
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
                expected = false,
            ),
        ).forEach { case ->
            assertEquals(
                case.expected,
                shouldRequireWirelessDebuggingForStart(
                    wirelessDebuggingStatus = case.wirelessDebuggingStatus,
                    managedWirelessAdbStatus = case.managedWirelessAdbStatus,
                ),
            )
        }
    }

    @Test
    fun wirelessAdbStartOptionsDoNotEnableConfiguredTcpPortWithoutActiveTcp() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            tcpPort = 4567,
        )

        assertEquals(false, options.tcpMode)
        assertEquals(4567, options.tcpPort)
        assertEquals(true, options.discoverPort)
        assertEquals(true, options.disableWirelessDebuggingAfterStart)
    }

    @Test
    fun wirelessAdbStartOptionsUseActiveTcpPortWithoutRestartingIt() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            tcpPort = 4567,
            activeTcpPort = 5555,
        )

        assertEquals(5555, options.port)
        assertEquals(false, options.tcpMode)
        assertEquals(false, options.discoverPort)
        assertEquals(4567, options.tcpPort)
        assertEquals(priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER, options.wirelessDebuggingControl)
    }

    @Test
    fun wirelessAdbStartOptionsDoNotEnableTcpWhenPolicyDisablesTcp() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
        )

        assertEquals(false, options.tcpMode)
        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun wirelessAdbStartOptionsCanDisableManagedWirelessDebugging() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
            managedWirelessAdbEnabled = false,
        )

        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun wirelessAdbStartOptionsDisableManagedWirelessDebuggingWhenPermissionIsUndeclared() {
        val options = privilegeUiWirelessAdbStartOptions(
            tcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            tcpPort = 4567,
            managedWirelessAdbEnabled = true,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNDECLARED,
        )

        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun staticTcpSwitchOptionsUseManagedWirelessDebuggingWhenAvailable() {
        val options = privilegeUiStaticTcpSwitchOptions(
            tcpPort = 4567,
            managedWirelessAdbEnabled = true,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.READY,
        )

        assertEquals(4567, options.tcpPort)
        assertEquals(true, options.discoverPort)
        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun staticTcpSwitchOptionsDisableManagedWirelessDebuggingWhenPermissionIsUndeclared() {
        val options = privilegeUiStaticTcpSwitchOptions(
            tcpPort = 4567,
            managedWirelessAdbEnabled = true,
            managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNDECLARED,
        )

        assertEquals(
            priv.kit.adb.PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun staticTcpStartCheckFailsBeforeStartWhenTcpModeIsClosed() {
        assertEquals(
            PrivilegeUiStaticTcpStartCheck.Failed,
            privilegeUiStaticTcpStartCheck(
                activeTcpPort = null,
                authorizationStatus = null,
            ),
        )
    }

    @Test
    fun staticTcpStartCheckFailsBeforeStartWhenPortIsUnavailable() {
        assertEquals(
            PrivilegeUiStaticTcpStartCheck.Failed,
            privilegeUiStaticTcpStartCheck(
                activeTcpPort = 5555,
                authorizationStatus = PrivilegeAdbAuthorizationStatus.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun staticTcpStartCheckUsesActiveAuthorizedPort() {
        assertEquals(
            PrivilegeUiStaticTcpStartCheck.Ready(5555),
            privilegeUiStaticTcpStartCheck(
                activeTcpPort = 5555,
                authorizationStatus = PrivilegeAdbAuthorizationStatus.AUTHORIZED,
            ),
        )
    }

    @Test
    fun managedWirelessAdbStatusIsHiddenWhenPermissionIsUndeclared() {
        assertFalse(
            PrivilegeUiManagedWirelessAdbStatus.UNDECLARED.isVisibleManagedWirelessAdbStatus(),
        )
    }

    @Test
    fun localNetworkPermissionOnlyAppliesFromAndroid17() {
        assertFalse(privilegeUiRequiresLocalNetworkPermissionForSdk(Build.VERSION_CODES.BAKLAVA))
        assertTrue(privilegeUiRequiresLocalNetworkPermissionForSdk(Build.VERSION_CODES.CINNAMON_BUN))
    }

    private data class PairingCheckCase(
        val wirelessDebuggingOn: Boolean,
        val pairingCheckPaired: Boolean?,
        val currentStatus: PrivilegeUiWirelessAdbStatus,
        val expected: PrivilegeUiWirelessAdbStatus,
    )

    private data class RefreshingPairingCase(
        val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
        val currentStatus: PrivilegeUiWirelessAdbStatus,
        val expected: PrivilegeUiWirelessAdbStatus,
    )

    private data class WirelessDebuggingRequirementCase(
        val wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
        val managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
        val expected: Boolean,
    )

}
