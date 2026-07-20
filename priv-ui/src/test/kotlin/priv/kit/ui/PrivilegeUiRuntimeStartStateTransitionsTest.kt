package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.runtime.finishRuntimeStartDisconnected
import priv.kit.ui.runtime.toConnectedRuntimeIdle
import priv.kit.ui.runtime.toDisconnectedRuntimeIdle

class PrivilegeUiRuntimeStartStateTransitionsTest {
    private val serverInfo = PrivilegeServerInfo(uid = 2000, pid = 123, protocolVersion = 1)

    @Test
    fun disconnectedCompletionPreservesAConnectionThatWonTheRace() {
        val connected = activeState().copy(runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED)

        assertEquals(
            connected.copy(
                busy = false,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                runtimeProgressMessage = null,
            ),
            connected.finishRuntimeStartDisconnected(),
        )
    }

    @Test
    fun disconnectedReducerClearsRuntimeAndPreservesConnectionSerial() {
        val active = activeState()

        assertEquals(
            active.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = null,
                adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                runtimeProgressMessage = null,
            ),
            active.toDisconnectedRuntimeIdle(),
        )
    }

    @Test
    fun connectedReducerPublishesServerAndNewConnectionSerial() {
        val active = activeState()

        assertEquals(
            active.copy(
                busy = false,
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
                runtimeStartSource = null,
                runtimeStartProviderId = null,
                serverInfo = serverInfo,
                adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                runtimeProgressMessage = null,
                connectionSerial = 8L,
            ),
            active.toConnectedRuntimeIdle(serverInfo = serverInfo, connectionSerial = 8L),
        )
    }

    private fun activeState(): PrivilegeUiState =
        PrivilegeUiState(
            busy = true,
            runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
            serverInfo = serverInfo,
            connectionSerial = 7L,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.RUNNING,
            runtimeStartProviderId = "provider",
            adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.RESTRICTED,
            runtimeProgressMessage = "starting",
        )
}
