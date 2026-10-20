package priv.kit.evdev.internal

import android.os.SystemClock
import priv.kit.evdev.PrivilegeEvdevTouchMapping
import priv.kit.evdev.PrivilegeEvdevWriteException
import priv.kit.evdev.PrivilegeEvdevWriteResult
import java.io.IOException
import kotlin.math.roundToInt

/** One handle's touch state. The session serializes calls and owns the fd. */
internal class PrivilegeEvdevTouch(
    private val native: PrivilegeEvdevBackend,
    private val fd: Int,
    val mapping: PrivilegeEvdevTouchMapping,
) {
    private val axes = native.bits(fd, 3).toSet()
    private val keys = native.bits(fd, 1).toSet()
    private val multi = 53 in axes && 54 in axes
    private val slots = multi && 47 in axes
    private val xAxis = if (multi) 53 else 0
    private val yAxis = if (multi) 54 else 1
    private val ranges = mutableMapOf<Int, IntArray>()
    private var active = false
    private var trackingId = 0

    init {
        require(xAxis in axes && yAxis in axes) { "Device has no touch position axes" }
        require(!slots || 57 in axes) { "Type B requires tracking IDs" }
        require(multi || 330 in keys || 24 in axes) { "Device has no touch contact signal" }
        for (axis in listOf(xAxis, yAxis, 0, 1, 24, 48, 58, 57).distinct().filter { it in axes }) {
            ranges[axis] = native.axis(fd, axis)
        }
        for (axis in listOf(xAxis, yAxis)) {
            val range = ranges.getValue(axis)
            require(range[2] > range[1]) { "Empty touch axis range" }
        }
        if (57 in axes) {
            val range = ranges.getValue(57)
            require(range[2] >= maxOf(0, range[1])) { "Invalid tracking ID range" }
            trackingId = maxOf(0, range[1])
        }
    }

    fun gesture(startX: Float, startY: Float, endX: Float, endY: Float,
                durationMillis: Long, holdMillis: Long, cancelled: () -> Boolean) {
        val start = point(startX, startY)
        val end = point(endX, endY)
        require(durationMillis in 0..Int.MAX_VALUE.toLong() && holdMillis in 0..Int.MAX_VALUE.toLong())
        check(!active) { "Previous touch release failed; close this device" }
        if (slots) check(native.slots(fd).all { it < 0 }) { "Device already has active touches" }
        check(!cancelled()) { "Input device is closing" }
        var failure: Throwable? = null
        try {
            // A partial DOWN may already have created a contact.
            active = true
            frame(start, down = true)
            waitUntil(SystemClock.uptimeMillis() + holdMillis, cancelled)
            val began = SystemClock.uptimeMillis()
            if (durationMillis > 0) {
                while (true) {
                    waitUntil(minOf(began + durationMillis, SystemClock.uptimeMillis() + 8), cancelled)
                    val elapsed = (SystemClock.uptimeMillis() - began).coerceAtMost(durationMillis)
                    val fraction = elapsed.toDouble() / durationMillis
                    frame(Pair(start.first + (end.first - start.first) * fraction,
                        start.second + (end.second - start.second) * fraction))
                    if (elapsed == durationMillis) break
                }
            } else if (start != end) frame(end)
        } catch (error: Throwable) {
            failure = if (error is InterruptedException) IOException("Touch gesture interrupted", error) else error
        } finally {
            try { release() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        if (failure?.cause is InterruptedException) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }

    private fun waitUntil(deadline: Long, cancelled: () -> Boolean) {
        while (true) {
            check(!cancelled()) { "Input device is closing" }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) return
            Thread.sleep(minOf(8, remaining))
        }
    }

    private fun point(x: Float, y: Float): Pair<Double, Double> {
        require(x >= 0 && y >= 0 &&
            x.toDouble() <= mapping.width - 1.0 && y.toDouble() <= mapping.height - 1.0) { "Coordinates outside display" }
        val u = x.toDouble() / (mapping.width - 1)
        val v = y.toDouble() / (mapping.height - 1)
        var (a, b) = when (mapping.rotation) {
            1 -> Pair(1 - v, u)
            2 -> Pair(1 - u, 1 - v)
            3 -> Pair(v, 1 - u)
            else -> Pair(u, v)
        }
        if (mapping.swapAxes) { val old = a; a = b; b = old }
        if (mapping.invertX) a = 1 - a
        if (mapping.invertY) b = 1 - b
        return Pair(a, b)
    }

    private fun frame(point: Pair<Double, Double>, down: Boolean = false) {
        val events = mutableListOf<Int>()
        fun add(type: Int, code: Int, value: Int) { events.add(type); events.add(code); events.add(value) }
        fun position(axis: Int, value: Double) {
            val range = ranges.getValue(axis)
            add(3, axis, (range[1] + value * (range[2].toDouble() - range[1])).roundToInt())
        }
        if (down) {
            if (330 in keys) add(1, 330, 1) // BTN_TOUCH must be first in the frame.
            if (325 in keys) add(1, 325, 1)
        }
        if (slots) add(3, 47, 0)
        if (multi && 57 in axes && (down || !slots)) add(3, 57, trackingId)
        position(xAxis, point.first)
        position(yAxis, point.second)
        if (multi && 0 in axes && 1 in axes) {
            position(0, point.first); position(1, point.second)
        }
        for (axis in listOf(24, 48, 58).filter { it in axes }) {
            val range = ranges.getValue(axis)
            add(3, axis, maxOf(1, range[1]).coerceAtMost(range[2]))
        }
        if (multi && !slots) add(0, 2, 0) // SYN_MT_REPORT
        add(0, 0, 0)
        write(events)
    }

    fun release() {
        if (!active) return
        val events = mutableListOf<Int>()
        fun add(type: Int, code: Int, value: Int) { events.add(type); events.add(code); events.add(value) }
        if (330 in keys) add(1, 330, 0)
        if (325 in keys) add(1, 325, 0)
        if (slots) { add(3, 47, 0); add(3, 57, -1) }
        if (24 in axes) add(3, 24, 0)
        if (slots && 58 in axes) add(3, 58, 0)
        if (multi && !slots) add(0, 2, 0)
        add(0, 0, 0)
        write(events)
        active = false
        if (57 in axes) {
            val range = ranges.getValue(57)
            trackingId = if (trackingId >= range[2]) maxOf(0, range[1]) else trackingId + 1
        }
    }

    private fun write(events: List<Int>) {
        val result = native.write(fd, events.toIntArray())
        if (result[0] != events.size / 3 || result[1] != 0) {
            throw PrivilegeEvdevWriteException(PrivilegeEvdevWriteResult(
                events.size / 3, result[0].takeIf { it >= 0 }, result[1].takeIf { it != 0 },
            ))
        }
    }
}
