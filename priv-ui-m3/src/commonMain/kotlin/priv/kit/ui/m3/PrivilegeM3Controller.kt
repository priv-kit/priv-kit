package priv.kit.ui.m3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import priv.kit.ui.ExperimentalPrivilegeUiApi
import priv.kit.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Material 3 presentation only. Business state remains owned by PrivilegeUiViewModel. */
@ExperimentalPrivilegeUiApi
public data class PrivilegeM3State(
    public val dismissedTcpFailureSerial: Long = 0,
    public val accessExplanation: PrivilegeM3AccessExplanation? = null,
    public val visibleAuthorization: PrivilegeUiAuthorization? = null,
    public val selectedStartupMode: PrivilegeUiStartupMode = PrivilegeUiStartupMode.ADB,
    public val pairingInputMethodsExpanded: Boolean = false,
    public val permissionDetailsConnection: Long? = null,
    public val stopConfirmationRequested: Boolean = false,
    public val tcpControlsExpanded: Boolean = false,
)

@ExperimentalPrivilegeUiApi
public class PrivilegeM3Actions(
    public val dismissTcpFailure: (Long) -> Unit,
    public val answerAccessExplanation: (Boolean) -> Unit,
    public val selectStartupMode: (PrivilegeUiStartupMode) -> Unit,
    public val setPairingInputMethodsExpanded: (Boolean) -> Unit,
    public val showPermissionDetails: (Long?) -> Unit,
    public val setStopConfirmationRequested: (Boolean) -> Unit,
    public val setTcpControlsExpanded: (Boolean) -> Unit,
)

/** Plain presentation controller, owned by the Android ViewModel or a preview scope. */
@ExperimentalPrivilegeUiApi
public class PrivilegeM3Controller(
    private val scope: CoroutineScope,
    initialState: PrivilegeM3State = PrivilegeM3State(),
) {
    public var state: PrivilegeM3State by mutableStateOf(initialState)
        private set

    private var explanationResult: CompletableDeferred<Boolean>? = null
    private var hintJob: Job? = null
    private var authorization: PrivilegeUiAuthorization? = null
    private var resumed = true
    private var focused = true

    internal suspend fun explainAccess(explanation: PrivilegeM3AccessExplanation): Boolean {
        val response = CompletableDeferred<Boolean>()
        explanationResult = response
        state = state.copy(accessExplanation = explanation)
        return try { response.await() } finally {
            response.cancel()
            explanationResult = null
            state = state.copy(accessExplanation = null)
        }
    }

    public fun updateAuthorization(value: PrivilegeUiAuthorization?) {
        if (authorization == value) return
        authorization = value
        updateHint()
    }

    public fun updateHostVisibility(resumed: Boolean, focused: Boolean) {
        if (this.resumed == resumed && this.focused == focused) return
        this.resumed = resumed
        this.focused = focused
        updateHint()
    }

    private fun updateHint() {
        hintJob?.cancel()
        state = state.copy(visibleAuthorization = null)
        val pending = authorization ?: return
        if (resumed && focused) return
        hintJob = scope.launch {
            delay(500)
            state = state.copy(visibleAuthorization = pending)
        }
    }

    public fun close() {
        explanationResult?.cancel()
        hintJob?.cancel()
    }

    public val actions: PrivilegeM3Actions = PrivilegeM3Actions(
        dismissTcpFailure = { state = state.copy(dismissedTcpFailureSerial = it) },
        answerAccessExplanation = { explanationResult?.complete(it) },
        selectStartupMode = { state = state.copy(selectedStartupMode = it) },
        setPairingInputMethodsExpanded = { state = state.copy(pairingInputMethodsExpanded = it) },
        showPermissionDetails = { state = state.copy(permissionDetailsConnection = it) },
        setStopConfirmationRequested = { state = state.copy(stopConfirmationRequested = it) },
        setTcpControlsExpanded = { state = state.copy(tcpControlsExpanded = it) },
    )
}

/** M3's explanation before navigating to system settings. */
@ExperimentalPrivilegeUiApi
public enum class PrivilegeM3AccessExplanation { NOTIFICATIONS, LOCAL_NETWORK, BATTERY_OPTIMIZATION }
