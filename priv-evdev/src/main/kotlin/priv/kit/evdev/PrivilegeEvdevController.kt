package priv.kit.evdev

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.system.ErrnoException
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import priv.kit.evdev.internal.InputCancellation
import java.io.Closeable
import java.io.IOException
import priv.kit.evdev.internal.IPrivilegeInputDeviceController
import priv.kit.evdev.internal.InputDeviceAccess
import priv.kit.evdev.internal.InputEndpoint
import priv.kit.evdev.internal.InputSession
import priv.kit.evdev.internal.PrivilegeInputError
import priv.kit.evdev.internal.PrivilegeInputResult

/**
 * Experimental evdev session, local or exported through a UserService.
 * One remote owner per session. Gestures suspend; queries, writes and close are synchronous.
 */
public class PrivilegeEvdevController private constructor(
    private val endpoint: IPrivilegeInputDeviceController,
    // Keep the death token alive for as long as this client exists.
    private val owner: IBinder?,
    private val local: InputSession? = null,
) : Closeable {
    @Volatile private var closed = false
    private var closeComplete = false

    public fun asBinder(): IBinder = endpoint.asBinder()

    /** Queries system touch-node associations. Requires createLocal(context) and input dump permission.
     * Unrecognized associations have null IDs. Does not change routing or open writable devices.
     */
    public fun queryTouchDevices(): List<PrivilegeEvdevTouchDeviceInfo> = call(
        { queryTouchDevices() }, { decode(queryTouchDevices()).devices },
    )

    public fun listDevices(): List<String> = call({ listDevices() }, { decode(listDevices()).paths })
    public fun openDevice(path: String, writable: Boolean = false): PrivilegeEvdevDevice {
        val handle = call({ openDevice(path, writable) }, { decode(openDevice(path, writable)).handle })
        return PrivilegeEvdevDevice(this, handle, path, writable)
    }

    /**
     * Opens a writable touch device and refreshes mapping from displayId for each gesture.
     * The local controller must be created with Context. Does not change input routing.
     */
    public fun openTouchDevice(path: String, displayId: Int = Display.DEFAULT_DISPLAY): PrivilegeEvdevDevice {
        val handle = call({ openTouchDevice(path, displayId) }, { decode(openTouchDevice(path, displayId)).handle })
        return PrivilegeEvdevDevice(this, handle, path, true)
    }

    /**
     * Opens the uniquely associated touch node for a logical display. Requires createLocal(context).
     * Input/display notifications invalidate the association; the next gesture re-matches it.
     * A change during a gesture aborts it without replay. Does not wake or reroute displays.
     */
    public fun openTouchDevice(displayId: Int = Display.DEFAULT_DISPLAY): PrivilegeEvdevTouchscreen {
        val handle = call({ openTouchscreen(displayId) }, { decode(openTouchscreen(displayId)).handle })
        return PrivilegeEvdevTouchscreen(PrivilegeEvdevDevice(this, handle, "", true), displayId)
    }

    internal fun configureTouch(handle: Int, mapping: PrivilegeEvdevTouchMapping): Unit = call(
        { configureTouch(handle, mapping) }, { decode(configureTouch(handle, mapping)) },
    )
    internal suspend fun touch(handle: Int, startX: Float, startY: Float, endX: Float, endY: Float,
                               durationMillis: Long, holdMillis: Long): Unit = withContext(Dispatchers.IO) {
        supervisorScope {
            check(!closed) { "Input controller is closed" }
            val signal = if (owner == null) InputCancellation() else endpoint.createTouchCancellation()
            val work = async(Dispatchers.IO) {
                call({ touch(handle, startX, startY, endX, endY, durationMillis, holdMillis) {
                    (signal as InputCancellation).cancelled
                } }, { decode(touch(handle, startX, startY, endX, endY, durationMillis, holdMillis, signal)); Unit })
            }
            try { work.await() } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try { signal.cancel() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    finally { work.join() }
                }
                throw failure
            }
        }
    }

    internal fun queryName(handle: Int): String = call(
        { queryName(handle) }, { decode(queryName(handle)).name },
    )
    internal fun queryCapabilities(handle: Int, type: Int): IntArray = call(
        { queryCapabilities(handle, type) }, { decode(queryCapabilities(handle, type)).values },
    )
    internal fun queryAxis(handle: Int, axis: Int): PrivilegeEvdevAxis = call({ queryAxis(handle, axis) }, {
        decode(queryAxis(handle, axis)).let {
            PrivilegeEvdevAxis(it.value, it.minimum, it.maximum, it.fuzz, it.flat, it.resolution)
        }
    })
    internal fun queryTrackingIds(handle: Int): IntArray = call(
        { queryTrackingIds(handle) }, { decode(queryTrackingIds(handle)).values },
    )
    internal fun writeEvents(handle: Int, events: IntArray): PrivilegeEvdevWriteResult = call(
        { writeEvents(handle, events) }, {
            decode(writeEvents(handle, events)).let {
                PrivilegeEvdevWriteResult(events.size / 3, it.submittedEventCount.takeIf { count -> count >= 0 },
                    it.errno.takeIf { errno -> errno != 0 })
            }
        },
    )

    private fun <T> call(operation: InputSession.() -> T, remote: IPrivilegeInputDeviceController.() -> T): T {
        check(!closed) { "Input controller is closed" }
        return if (owner == null) checkNotNull(local).operation() else endpoint.remote()
    }

    @Synchronized
    internal fun closeDevice(handle: Int) {
        if (!closeComplete) {
            if (local != null) local.closeDevice(handle) else decode(endpoint.closeDevice(handle))
        }
    }

    /** Idempotent. After failure, only explicit close retries are allowed; no automatic retry. */
    @Synchronized
    override fun close() {
        if (closeComplete) return
        closed = true
        if (local != null) local.close() else decode(endpoint.close())
        closeComplete = true
    }

    public companion object {
        /** Create in a process with device access. Export with [asBinder]. */
        public fun createLocal(): PrivilegeEvdevController = createLocal(InputDeviceAccess())

        /** Enables automatic display mapping for [openTouchDevice] in this process. */
        public fun createLocal(context: Context): PrivilegeEvdevController = createLocal(InputDeviceAccess(context))

        internal fun createLocal(access: InputDeviceAccess): PrivilegeEvdevController {
            val session = InputSession(access)
            return PrivilegeEvdevController(InputEndpoint(session), null, session)
        }

        /** Attach once to a same-version controller. The caller becomes its remote owner. */
        public fun fromBinder(binder: IBinder): PrivilegeEvdevController {
            val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(binder)
            val owner = Binder()
            decode(endpoint.attach(owner))
            return PrivilegeEvdevController(endpoint, owner)
        }
    }
}

private fun <T : PrivilegeInputResult> decode(result: T): T {
    val error = result.error ?: return result
    when (error.code) {
        PrivilegeInputError.DEVICE -> throw PrivilegeEvdevSyscallException(
            error.errno, error.message, ErrnoException("remote input", error.errno),
        )
        PrivilegeInputError.TARGET -> throw PrivilegeEvdevTargetException(error.displayId, error.message)
        PrivilegeInputError.WRITE -> throw PrivilegeEvdevWriteException(PrivilegeEvdevWriteResult(
            error.requestedEventCount, error.submittedEventCount, error.errno.takeIf { it != 0 },
        ))
        PrivilegeInputError.IO -> throw IOException(error.message)
        PrivilegeInputError.ARGUMENT -> throw IllegalArgumentException(error.message)
        PrivilegeInputError.STATE -> throw IllegalStateException(error.message)
        else -> throw IOException("Unknown input error code: ${error.code}")
    }
}

