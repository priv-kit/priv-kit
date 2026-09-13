package priv.kit.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchAction
import kotlin.test.*
import kotlin.io.encoding.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeUiSimulationTest {
    private fun model(scope: CoroutineScope, copy: (String) -> Unit = {}) =
        PrivilegeUiSimulation(scope, "Simulated provider", PrivilegeUiRuntimeStartSource.entries.associateWith { "Starting $it" }, "Enter six digits", copy)

    @Test
    fun rootRestartRequiresConfirmationAndStopClearsRecovery() = runTest {
        val model = model(this)
        model.actions.startRoot()
        assertEquals(PrivilegeUiRuntimeStatus.STARTING, model.state.runtimeStatus)
        advanceUntilIdle()
        assertEquals(0, model.state.serverUid)
        assertTrue(model.state.desiredEnabled)
        model.actions.startRoot()
        assertEquals(PrivilegeUiServerRestartRequest.Root, model.state.restartConfirmationTarget)
        model.actions.cancelServerRestart()
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, model.state.runtimeStatus)
        model.actions.startRoot()
        model.actions.confirmServerRestart()
        advanceUntilIdle()
        model.actions.stopServer()
        assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, model.state.runtimeStatus)
        assertNull(model.state.serverUid)
        assertFalse(model.state.desiredEnabled)
    }

    @Test
    fun cancelledStartupCannotCompleteOrAffectItsReplacement() = runTest {
        val model = model(this)
        model.actions.startRoot()
        runCurrent()
        advanceTimeBy(400)
        model.actions.stopCurrentStart()
        model.actions.startStaticTcpAdb()
        model.actions.confirmStaticTcpSwitch()
        advanceUntilIdle()
        assertEquals(2000, model.state.serverUid)
        assertEquals(PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP, model.state.runtimeStartSource)
        assertFalse(model.state.startupLogLines.any { "uid=0" in it })
    }

    @Test
    fun wirelessStartWaitsForValidPairingAndThenContinues() = runTest {
        val model = model(this)
        model.actions.startWirelessAdb()
        assertTrue(model.state.pairingDialogVisible)
        model.actions.updatePairingCode("12abc3")
        model.actions.submitNotificationPairingCode()
        advanceUntilIdle()
        assertEquals(PrivilegeUiAdbPairingStatus.FOUND, model.state.pairingStatus)
        model.actions.updatePairingCode("123456")
        model.actions.submitNotificationPairingCode()
        assertEquals(PrivilegeUiAdbPairingStatus.PAIRING, model.state.pairingStatus)
        advanceUntilIdle()
        assertFalse(model.state.pairingDialogVisible)
        assertEquals(PrivilegeUiAdbPairingStatus.PAIRED, model.state.pairingStatus)
        assertEquals(PrivilegeUiRuntimeStartSource.ADB_WIRELESS, model.state.runtimeStartSource)
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, model.state.runtimeStatus)
    }

    @Test
    fun cancellingPairingDoesNotAuthorizeOrStartLater() = runTest {
        val model = model(this)
        model.actions.startWirelessAdb()
        model.actions.updatePairingCode("654321")
        model.actions.submitNotificationPairingCode()
        runCurrent()
        model.actions.stopNotificationPairing()
        advanceUntilIdle()
        assertFalse(model.state.busy)
        assertFalse(model.state.pairingDialogVisible)
        assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, model.state.pairingStatus)
        assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, model.state.runtimeStatus)
    }

    @Test
    fun standalonePairingDoesNotStartAServer() = runTest {
        val model = model(this)
        model.actions.startNotificationPairing()
        model.actions.updatePairingCode("111111")
        model.actions.submitNotificationPairingCode()
        advanceUntilIdle()
        assertEquals(PrivilegeUiAdbPairingStatus.PAIRED, model.state.pairingStatus)
        assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, model.state.runtimeStatus)
    }

    @Test
    fun tcpChangesRequireConfirmationAndDisconnectAdbButNotRoot() = runTest {
        val model = model(this)
        model.actions.startStaticTcpAdb()
        assertEquals(PrivilegeUiStaticTcpSwitchAction.START_SERVICE, model.state.staticTcpSwitchConfirmation)
        model.actions.cancelStaticTcpSwitch()
        assertNull(model.state.staticTcp.activePort)
        model.actions.startStaticTcpAdb()
        model.actions.confirmStaticTcpSwitch()
        advanceUntilIdle()
        assertEquals(5555, model.state.staticTcp.activePort)
        model.actions.restartTcpMode()
        assertEquals(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT, model.state.staticTcpSwitchConfirmation)
        model.actions.confirmStaticTcpSwitch()
        advanceUntilIdle()
        assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, model.state.runtimeStatus)
        assertTrue(model.state.desiredEnabled)
        model.actions.disableAutoRecovery()
        assertFalse(model.state.desiredEnabled)
        model.actions.startRoot()
        advanceUntilIdle()
        model.actions.disableTcpMode()
        assertNull(model.state.staticTcp.activePort)
        assertEquals(0, model.state.serverUid)
    }

    @Test
    fun externalAuthorizationCanBeCancelledAndIsRememberedWithinSession() = runTest {
        val model = model(this)
        model.actions.authorizeOrStartExternal("simulation")
        assertTrue(model.externalAuthorizationRequested)
        model.cancelOperation()
        assertFalse(model.state.externalStartItems.single().snapshot.authorized)
        model.actions.authorizeOrStartExternal("simulation")
        model.confirmExternalAuthorization()
        advanceUntilIdle()
        assertTrue(model.state.externalStartItems.single().snapshot.authorized)
        assertEquals(PrivilegeUiRuntimeStartSource.EXTERNAL, model.state.runtimeStartSource)
        model.actions.stopServer()
        model.actions.authorizeOrStartExternal("simulation")
        assertFalse(model.externalAuthorizationRequested)
        advanceUntilIdle()
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, model.state.runtimeStatus)
    }

    @Test
    fun manualCopyDoesNotExecuteAndManualStartHasNoRecovery() = runTest {
        var copied = ""
        val model = model(this) { copied = it }
        model.actions.selectStartupMode(PrivilegeUiStartupMode.MANUAL_SHELL)
        model.actions.copyManualCommand()
        assertEquals(model.state.manualShellCommandLine, copied)
        assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, model.state.runtimeStatus)
        model.actions.startInteractive()
        advanceUntilIdle()
        assertEquals(2000, model.state.serverUid)
        assertFalse(model.state.desiredEnabled)
        model.actions.copyStartupLog()
        assertContains(copied, "MANUAL_SHELL")
        model.actions.clearStartupLog()
        assertTrue(model.state.startupLogLines.isEmpty())
    }

    @Test
    fun packagingChangesOnlyTheCommandAndKeepsValidSessionInstallTokens() = runTest {
        var copied = ""
        val model = model(this) { copied = it }
        val legacy = assertNotNull(model.state.manualShellCommandLine)
        val match = assertNotNull(Regex(
            "adb shell (/data/app/~~([A-Za-z0-9_-]{22}==)/priv\\.kit\\.sample-([A-Za-z0-9_-]{22}==))/lib/arm64/libprivkitstarter\\.so",
        ).matchEntire(legacy))
        for (token in match.groupValues.drop(2)) {
            val bytes = Base64.UrlSafe.decode(token)
            assertEquals(16, bytes.size)
            assertEquals(token, Base64.UrlSafe.encode(bytes))
        }
        assertNotEquals(legacy, model(this).state.manualShellCommandLine)
        model.actions.startWirelessAdb()
        model.actions.updatePairingCode("123456")
        val pairing = model.state
        model.setUseLegacyPackaging(false)
        val modern = "adb shell /system/bin/linker64 '${match.groupValues[1]}/base.apk!/lib/arm64-v8a/libprivkitstarter.so'"
        assertEquals(pairing.copy(manualShellCommandLine = modern), model.state)
        model.actions.copyManualCommand()
        assertEquals(modern, copied)
        model.actions.submitNotificationPairingCode()
        advanceUntilIdle()
        val connected = model.state
        model.setUseLegacyPackaging(true)
        assertEquals(connected.copy(manualShellCommandLine = legacy), model.state)
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, model.state.runtimeStatus)
    }

    @Test
    fun leavingTheHostCancelsPendingWork() = runTest {
        val host = Job()
        val model = model(CoroutineScope(coroutineContext + host))
        model.actions.startRoot()
        runCurrent()
        host.cancel()
        advanceUntilIdle()
        assertNull(model.state.serverUid)
        assertFalse(model.state.startupLogLines.any { "Connected" in it })
    }
}
