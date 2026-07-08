package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiRuntimeStartSession(
    val generation: Long,
) : AutoCloseable {
    private val lock = Any()
    private val closeables = mutableListOf<AutoCloseable>()
    val stop = AtomicBoolean(false)

    val active: Boolean
        get() = !stop.get()

    fun addCloseable(closeable: AutoCloseable) {
        val closeNow = synchronized(lock) {
            if (stop.get()) {
                true
            } else {
                closeables += closeable
                false
            }
        }
        if (closeNow) {
            closeable.close()
        }
    }

    fun checkActive() {
        if (!active) {
            throw CancellationException("Runtime start session was interrupted")
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (!stop.compareAndSet(false, true)) {
                emptyList()
            } else {
                closeables.toList().also { closeables.clear() }
            }
        }
        toClose.forEach { it.close() }
    }
}
