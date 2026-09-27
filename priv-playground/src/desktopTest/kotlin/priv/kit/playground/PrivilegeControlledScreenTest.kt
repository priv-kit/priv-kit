package priv.kit.playground

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import priv.kit.ui.*
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
                val state = mutableStateOf(PrivilegeUiScreenState(selectedStartupMode = PrivilegeUiStartupMode.ROOT))
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
                    dismissTcpAuthorizationFailureDialog = { error("Unexpected action: dismissTcpAuthorizationFailureDialog") },
                    enableTcpMode = { error("Unexpected action: enableTcpMode") },
                    openNotificationSettings = { error("Unexpected action: openNotificationSettings") },
                    requestLocalNetworkPermission = { error("Unexpected action: requestLocalNetworkPermission") },
                    requestBatteryOptimization = { error("Unexpected action: requestBatteryOptimization") },
                    restartTcpMode = { error("Unexpected action: restartTcpMode") },
                    selectStartupMode = { state.value = state.value.copy(selectedStartupMode = it) },
                    startInteractive = { starts++ },
                    startNotificationPairing = { error("Unexpected action: startNotificationPairing") },
                    startRoot = { starts++ },
                    startStaticTcpAdb = { error("Unexpected action: startStaticTcpAdb") },
                    startWirelessAdb = { error("Unexpected action: startWirelessAdb") },
                    stopCurrentStart = { error("Unexpected action: stopCurrentStart") },
                    stopNotificationPairing = { error("Unexpected action: stopNotificationPairing") },
                    stopServer = { error("Unexpected action: stopServer") },
                    submitNotificationPairingCode = { error("Unexpected action: submitNotificationPairingCode") },
                    updatePairingCode = { error("Unexpected action: updatePairingCode") },
                )
                setContent {
                    MaterialTheme {
                        PrivilegeScreen(
                            state.value, actions, showFeedback = { error(it) }, systemPrompt = prompt.value,
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
            }
        } finally { Locale.setDefault(previousLocale) }
    }
}
