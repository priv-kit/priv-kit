package priv.kit.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal enum class PrivilegeUiStatusRefreshState {
    IDLE,
    RUNNING,
}

internal class PrivilegeUiStatusRefreshController(
    private val scope: CoroutineScope,
    private val name: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val stateHolder = MutableStateFlow(PrivilegeUiStatusRefreshState.IDLE)

    val state: StateFlow<PrivilegeUiStatusRefreshState> =
        stateHolder.asStateFlow()

    val loading: Boolean
        get() = state.value == PrivilegeUiStatusRefreshState.RUNNING

    fun start(action: suspend () -> Unit): Boolean {
        if (!markRunning()) return false
        if (!scope.isActive) {
            finish()
            return false
        }
        scope.launch(dispatcher + CoroutineName(name)) {
            try {
                action()
            } finally {
                finish()
            }
        }
        return true
    }

    fun run(action: () -> Unit): Boolean {
        if (!markRunning()) return false
        try {
            action()
        } finally {
            finish()
        }
        return true
    }

    suspend fun join(timeoutMillis: Long): Boolean {
        if (!loading) return true
        if (timeoutMillis <= 0L) return !loading
        return withTimeoutOrNull(timeoutMillis.milliseconds) {
            state.first { it == PrivilegeUiStatusRefreshState.IDLE }
            true
        } ?: false
    }

    private fun markRunning(): Boolean =
        synchronized(lock) {
            if (stateHolder.value == PrivilegeUiStatusRefreshState.RUNNING) {
                false
            } else {
                stateHolder.value = PrivilegeUiStatusRefreshState.RUNNING
                true
            }
        }

    private fun finish() {
        synchronized(lock) {
            stateHolder.value = PrivilegeUiStatusRefreshState.IDLE
        }
    }
}
