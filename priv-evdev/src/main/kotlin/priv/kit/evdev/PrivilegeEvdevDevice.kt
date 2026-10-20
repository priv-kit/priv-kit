package priv.kit.evdev

import java.io.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A local or controller-backed device handle. Device I/O is serialized per handle. Cancelling a gesture waits for contact release.
 * Convenience gestures release their contacts. Raw writes remain caller-managed.
 */
public class PrivilegeEvdevDevice internal constructor(
    private val controller: PrivilegeEvdevController,
    private val handle: Int,
    public val path: String,
    public val writable: Boolean,
) : Closeable {
    private val gestureLock = Mutex()
    private var closed = false
    private var closeComplete = false

    /** Detects touch protocol and configures screen-pixel mapping. Disables automatic mapping. Requires a writable device. */
    public fun configureTouch(mapping: PrivilegeEvdevTouchMapping): Unit = query {
        configureTouch(handle, mapping)
    }

    /** Tap in configured display pixels. No physical touches may overlap this operation. */
    public suspend fun tap(x: Float, y: Float): Unit = gesture { touch(handle, x, y, x, y, 0, 0) }

    /** Single contact swipe. Negative duration selects 300 ms; zero moves immediately. */
    public suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long = 300): Unit = gesture {
        touch(handle, startX, startY, endX, endY, if (durationMillis < 0) 300 else durationMillis, 0)
    }

    /** Hold, then swipe. durationMillis is movement time, excluding longPressMillis. */
    public suspend fun dragAndDrop(startX: Float, startY: Float, endX: Float, endY: Float,
                           durationMillis: Long = 300, longPressMillis: Long = 500): Unit = gesture {
        touch(handle, startX, startY, endX, endY, if (durationMillis < 0) 300 else durationMillis, longPressMillis)
    }

    public fun queryName(): String = query { queryName(handle) }

    /** type=0 returns event types; other types return supported event codes. */
    public fun queryCapabilities(type: Int = 0): Set<Int> = query {
        queryCapabilities(handle, type).toSet()
    }

    public fun queryAxis(axis: Int): PrivilegeEvdevAxis = query {
        queryAxis(handle, axis)
    }

    /** Type B tracking IDs in slot order; -1 means inactive. This snapshot is not a lock. */
    public fun queryTrackingIds(): List<Int> = query { queryTrackingIds(handle).toList() }

    /**
     * Submits 1..256 events in one syscall without retries. Include SYN_REPORT explicitly.
     * Partial/unknown results require application-specific recovery, not replay.
     */
    public fun writeEvents(events: List<PrivilegeEvdevEvent>): PrivilegeEvdevWriteResult = query {
        check(writable) { "Device was opened read-only" }
        val snapshot = events.toList()
        require(snapshot.size in 1..256) { "Expected 1..256 events" }
        val packed = IntArray(snapshot.size * 3)
        snapshot.forEachIndexed { index, event ->
            packed[index * 3] = event.type
            packed[index * 3 + 1] = event.code
            packed[index * 3 + 2] = event.value
        }
        writeEvents(handle, packed)
    }

    private suspend fun gesture(block: suspend PrivilegeEvdevController.() -> Unit) = gestureLock.withLock {
        synchronized(this) { check(!closed) { "Input device is closed" } }
        controller.block()
    }

    @Synchronized
    private fun <T> query(block: PrivilegeEvdevController.() -> T): T {
        check(!closed) { "Input device is closed" }
        return controller.block()
    }

    /** Idempotent; after failure, only explicit close retries are allowed. Does not stop the process. */
    @Synchronized
    override fun close() {
        if (closeComplete) return
        closed = true
        controller.closeDevice(handle)
        closeComplete = true
    }
}
