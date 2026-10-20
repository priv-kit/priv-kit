package priv.kit.evdev

import java.io.Closeable

/**
 * An experimental evdev handle bound to a logical display. Re-matches its node after
 * input/display notifications. Missing or ambiguous associations fail without fallback.
 * Gestures suspend; cancellation waits for contact release.
 */
public class PrivilegeEvdevTouchscreen internal constructor(
    private val device: PrivilegeEvdevDevice,
    public val displayId: Int,
) : Closeable {
    public suspend fun tap(x: Float, y: Float): Unit = device.tap(x, y)

    public suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float,
                     durationMillis: Long = 300): Unit =
        device.swipe(startX, startY, endX, endY, durationMillis)

    public suspend fun dragAndDrop(startX: Float, startY: Float, endX: Float, endY: Float,
                           durationMillis: Long = 300, longPressMillis: Long = 500): Unit =
        device.dragAndDrop(startX, startY, endX, endY, durationMillis, longPressMillis)

    override fun close(): Unit = device.close()
}
