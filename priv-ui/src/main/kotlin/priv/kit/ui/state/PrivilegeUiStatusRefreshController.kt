package priv.kit.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiStatusRefreshController(
    private val scope: CoroutineScope,
    private val name: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val loadingHolder = MutableStateFlow(false)

    val loading: Boolean
        get() = loadingHolder.value

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
            loadingHolder.first { !it }
            true
        } ?: false
    }

    private fun markRunning(): Boolean =
        synchronized(lock) {
            if (loadingHolder.value) {
                false
            } else {
                loadingHolder.value = true
                true
            }
        }

    private fun finish() {
        synchronized(lock) {
            loadingHolder.value = false
        }
    }
}
