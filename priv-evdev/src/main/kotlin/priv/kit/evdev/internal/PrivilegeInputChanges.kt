package priv.kit.evdev.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

internal interface InputChanges : Closeable {
    val version: Long
    fun touchVersion(displayId: Int, inputDeviceId: Int?): Long
}

/** Callbacks only invalidate snapshots; no dump or gesture work runs on this thread. */
internal class PrivilegeInputChanges(context: Context) : InputChanges,
    InputManager.InputDeviceListener, DisplayManager.DisplayListener {
    private val inputs = checkNotNull(context.getSystemService(InputManager::class.java))
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val thread = HandlerThread("input-config")
    private val generation = AtomicLong()
    private val inputVersions = mutableMapOf<Int, Long>()
    private val displayVersions = mutableMapOf<Int, Long>()
    private var inputRegistered = false
    private var displayRegistered = false
    override val version: Long get() = generation.get()

    @Synchronized
    override fun touchVersion(displayId: Int, inputDeviceId: Int?): Long =
        maxOf(inputVersions[inputDeviceId] ?: 0, displayVersions[displayId] ?: 0)

    init {
        val identity = Binder.clearCallingIdentity()
        try {
            thread.start()
            val handler = Handler(thread.looper)
            inputs.registerInputDeviceListener(this, handler)
            inputRegistered = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    displays.registerDisplayListener(
                        { task -> handler.post(task) },
                        DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED or
                            DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or DisplayManager.EVENT_TYPE_DISPLAY_STATE,
                        this,
                    )
                } else {
                    displays.registerDisplayListener(this, handler)
                }
                displayRegistered = true
            } catch (error: Throwable) {
                try { inputs.unregisterInputDeviceListener(this) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    private fun inputChanged(deviceId: Int) { inputVersions[deviceId] = generation.incrementAndGet() }
    @Synchronized
    private fun displayChanged(displayId: Int) { displayVersions[displayId] = generation.incrementAndGet() }
    override fun onInputDeviceAdded(deviceId: Int) = inputChanged(deviceId)
    override fun onInputDeviceRemoved(deviceId: Int) = inputChanged(deviceId)
    override fun onInputDeviceChanged(deviceId: Int) = inputChanged(deviceId)
    override fun onDisplayAdded(displayId: Int) = displayChanged(displayId)
    override fun onDisplayRemoved(displayId: Int) = displayChanged(displayId)
    // Geometry/state are checked on the selected display during the gesture. Refresh rate
    // and changes on other displays only invalidate the next association query.
    override fun onDisplayChanged(displayId: Int) { generation.incrementAndGet() }

    override fun close() {
        val identity = Binder.clearCallingIdentity()
        try {
            try {
                if (inputRegistered) {
                    inputs.unregisterInputDeviceListener(this)
                    inputRegistered = false
                }
            } finally {
                if (displayRegistered) {
                    displays.unregisterDisplayListener(this)
                    displayRegistered = false
                }
            }
        } finally {
            thread.quitSafely()
            Binder.restoreCallingIdentity(identity)
        }
    }
}
