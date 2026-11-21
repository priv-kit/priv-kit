package priv.kit.playground

import priv.kit.ui.m3.PrivilegeUiPromptState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import priv.kit.ui.*
import priv.kit.ui.m3.*
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PrivilegeControlledScreenTest {
    @Test
    fun hostOwnsTransitionsAndPromptWithoutImplicitSimulation() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            runSkikoComposeUiTest(size = Size(480f, 1000f)) {
                val state = mutableStateOf(PrivilegeUiScreenState())
                val m3 = PrivilegeM3Controller(
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                    PrivilegeM3State(selectedStartupMode = PrivilegeUiStartupMode.ROOT),
                )
                val prompt = mutableStateOf<PrivilegeUiPromptState?>(null)
                val promptTop = mutableStateOf(0)
                var starts = 0
                val actions = PrivilegeUiActions(
                    authorizeOrStartExternal = { error("Unexpected action: authorizeOrStartExternal") },
                    canInteract = { true },
                    cancelPendingPairingStart = { error("Unexpected action: cancelPendingPairingStart") },
                    cancelServerRestart = { error("Unexpected action: cancelServerRestart") },
                    cancelStaticTcpSwitch = { error("Unexpected action: cancelStaticTcpSwitch") },
                    clearStartupLog = { error("Unexpected action: clearStartupLog") },
                    confirmServerRestart = { error("Unexpected action: confirmServerRestart") },
                    confirmStaticTcpSwitch = { error("Unexpected action: confirmStaticTcpSwitch") },
                    continuePairingWithoutNotification = { error("Unexpected action: continuePairingWithoutNotification") },
                    copyManualCommand = { error("Unexpected action: copyManualCommand") },
                    copyStartupLog = { error("Unexpected action: copyStartupLog") },
                    copyStaticTcpCommand = { error("Unexpected action: copyStaticTcpCommand") },
                    disableAutoRecovery = { error("Unexpected action: disableAutoRecovery") },
                    disableTcpMode = { error("Unexpected action: disableTcpMode") },
                    enableTcpMode = { error("Unexpected action: enableTcpMode") },
                    requestLocalNetworkPermission = { error("Unexpected action: requestLocalNetworkPermission") },
                    requestBatteryOptimization = { error("Unexpected action: requestBatteryOptimization") },
                    restartTcpMode = { error("Unexpected action: restartTcpMode") },
                    startInteractive = { starts++ },
                    startNotificationPairing = { error("Unexpected action: startNotificationPairing") },
                    startRoot = { starts++ },
                    startStaticTcpAdb = { error("Unexpected action: startStaticTcpAdb") },
                    startWirelessAdb = { error("Unexpected action: startWirelessAdb") },
                    stopCurrentStart = { error("Unexpected action: stopCurrentStart") },
                    cancelPairing = { error("Unexpected action: cancelPairing") },
                    stopServer = { error("Unexpected action: stopServer") },
                    submitNotificationPairingCode = { error("Unexpected action: submitNotificationPairingCode") },
                    updatePairingCode = { error("Unexpected action: updatePairingCode") },
                )
                setContent {
                    MaterialTheme {
                        PrivilegeM3Screen(
                            state.value, actions, m3.state, m3.actions, showFeedback = { error(it) }, systemPrompt = prompt.value,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            systemPromptWindowInsets = WindowInsets(0, promptTop.value, 0, 0),
                        )
                    }
                }
                onNodeWithText("Not started").assertExists()
                onNodeWithText("Start").performClick()
                runOnIdle { assertEquals(1, starts) }
                mainClock.advanceTimeBy(2000)
                onNodeWithText("Not started").assertExists()
                runOnIdle {
                    state.value = state.value.copy(runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                        runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT, serverUid = 0)
                    prompt.value = PrivilegeUiPromptState("Host prompt", "Host guidance")
                }
                onNodeWithText("Started").assertExists()
                onNodeWithText("Host guidance").assertExists()
                val promptY = onNodeWithText("Host guidance").fetchSemanticsNode().boundsInRoot.top
                val contentY = onNodeWithText("Started").fetchSemanticsNode().boundsInRoot.top
                runOnIdle { promptTop.value = 80 }
                assertEquals(promptY + 80f, onNodeWithText("Host guidance").fetchSemanticsNode().boundsInRoot.top, 0.5f)
                assertEquals(contentY, onNodeWithText("Started").fetchSemanticsNode().boundsInRoot.top, 0.5f)
                runOnIdle { promptTop.value = 0 }
                assertEquals(promptY, onNodeWithText("Host guidance").fetchSemanticsNode().boundsInRoot.top, 0.5f)
                runOnIdle {
                    state.value = state.value.copy(runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED, serverUid = null)
                    prompt.value = null
                }
                onNodeWithText("Not started").assertExists()
                onNodeWithText("Host guidance").assertDoesNotExist()
                onNodeWithText("ADB").performClick().assertIsSelected()
                runOnIdle {
                    m3.actions.selectStartupMode(PrivilegeUiStartupMode.ROOT)
                    state.value = state.value.copy(
                        pairingInteraction = PrivilegeUiPairingInteraction.ENTER_CODE,
                        pairingStatus = PrivilegeUiAdbPairingStatus.FOUND,
                    )
                }
                // Pending ADB input must still render when the selected panel is Root.
                onNodeWithText("Pairing code").assertExists()
                runOnIdle {
                    state.value = state.value.copy(pairingInteraction = null)
                }
                onNodeWithText("Pairing code").assertDoesNotExist()
                runOnIdle { state.value = state.value.copy(tcpAuthorizationFailureSerial = 1) }
                onNodeWithText("ADB key authorization").assertExists()
                onNodeWithText("OK").performClick()
                onNodeWithText("ADB key authorization").assertDoesNotExist()
                runOnIdle { assertEquals(1L, state.value.tcpAuthorizationFailureSerial) }
                // A second failure is a fresh result even though the first was dismissed.
                runOnIdle { state.value = state.value.copy(tcpAuthorizationFailureSerial = 2) }
                onNodeWithText("ADB key authorization").assertExists()
            }
        } finally { Locale.setDefault(previousLocale) }
    }
}
