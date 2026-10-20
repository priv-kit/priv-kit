package priv.kit.evdev.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.view.Display
import java.io.Closeable
import java.io.File
import java.io.IOException
import priv.kit.evdev.PrivilegeEvdevTargetException
import priv.kit.evdev.PrivilegeEvdevWriteException
import priv.kit.evdev.PrivilegeEvdevAxis
import priv.kit.evdev.PrivilegeEvdevEvent
import priv.kit.evdev.PrivilegeEvdevSyscallException
import priv.kit.evdev.PrivilegeEvdevTouchDeviceInfo
import priv.kit.evdev.PrivilegeEvdevTouchMapping
import priv.kit.evdev.PrivilegeEvdevWriteResult
import priv.kit.evdev.internal.PrivilegeInputWriteResult as WireWriteResult
import priv.kit.evdev.internal.queryTouchDevices as querySystemTouchDevices

/** System access shared by the device session; native loading remains deferred until device I/O. */
internal open class InputDeviceAccess(private val context: Context? = null) {
    open val native: PrivilegeEvdevBackend get() = PrivilegeEvdevNative
    private fun context(): Context = checkNotNull(context) { "Create the local controller with Context for automatic mapping and device queries" }
    private val displays by lazy { checkNotNull(context().getSystemService(DisplayManager::class.java)) }
    open fun list(): List<String> = listInputDevices()
    open fun displayMapping(displayId: Int): PrivilegeEvdevTouchMapping = readTouchMapping(displays, displayId)
    open fun touchDevices(): List<PrivilegeEvdevTouchDeviceInfo> = querySystemTouchDevices(context())
    open fun startChanges(): InputChanges = PrivilegeInputChanges(context())
    open fun displayState(displayId: Int): Int = readDisplayState(displays, displayId)
}

/** Registry lock protects ownership; per-device locks serialize I/O with cleanup. */
internal class InputSession(private val access: InputDeviceAccess) : Closeable {
    private class Device(val writable: Boolean) {
        var fd = -1
        var path = ""
        @Volatile var closing = false
        var released = true
        var touch: PrivilegeEvdevTouch? = null
        var displayId: Int? = null
        var associationVersion: Long? = null
        var inputDeviceId: Int? = null
        var touchVersion = 0L
    }
    private val native get() = access.native
    private val devices = mutableMapOf<Int, Device>()
    private val closeLock = Any()
    private val associationLock = Any()
    private var changes: InputChanges? = null
    private var changesClosed = false
    private var cachedVersion = -1L
    private var cachedDevices = emptyList<PrivilegeEvdevTouchDeviceInfo>()
    private var nextHandle = 0
    @Volatile private var closed = false
    private var owner: IBinder? = null
    private var ownerUid = -1
    private var ownerPid = -1
    private val death = IBinder.DeathRecipient { runCatching { close() } }

    @Synchronized
    fun attach(owner: IBinder) {
        check(!closed) { "Input controller is closed" }
        check(this.owner == null) { "Input controller already has an owner" }
        // A Binder delivered by the application is the initial capability. Pin the first
        // owner to its UID and PID; do not make this endpoint a published system service.
        owner.linkToDeath(death, 0)
        this.owner = owner
        ownerUid = Binder.getCallingUid()
        ownerPid = Binder.getCallingPid()
    }

    private fun <T> execute(block: () -> T): T {
        return try { block() } catch (error: ErrnoException) {
            throw PrivilegeEvdevSyscallException(error.errno, error.message, error)
        }
    }

    fun <T> remote(block: () -> T): T {
        synchronized(this) {
            val pid = Binder.getCallingPid()
            if (pid != Process.myPid() && (ownerUid < 0 || Binder.getCallingUid() != ownerUid || pid != ownerPid)) {
                throw SecurityException("Caller does not own this input controller")
            }
        }
        return block()
    }

    @Synchronized
    fun listDevices(): List<String> = execute {
        check(!closed) { "Input controller is closed" }
        val paths = access.list()
        check(paths.size <= 1024) { "Too many input devices" }
        paths
    }
    fun queryTouchDevices(): List<PrivilegeEvdevTouchDeviceInfo> {
        check(!closed) { "Input controller is closed" }
        return access.touchDevices()
    }
    fun openDevice(path: String, writable: Boolean): Int = open(path, writable)

    fun openTouchDevice(path: String, displayId: Int): Int {
        require(displayId >= 0) { "Expected a logical display ID" }
        val mapping = access.displayMapping(displayId)
        return open(path, true, displayId, mapping)
    }

    private fun selectDevice(displayId: Int): Pair<Long, PrivilegeEvdevTouchDeviceInfo> = synchronized(associationLock) {
        val monitor = synchronized(this) {
            check(!closed) { "Input controller is closed" }
            changes ?: access.startChanges().also { changes = it }
        }
        val version = monitor.version
        if (cachedVersion != version) {
            val snapshot = queryTouchDevices()
            if (monitor.version != version) throw PrivilegeEvdevTargetException(displayId, "Input configuration changed during device query")
            cachedDevices = snapshot
            cachedVersion = version
        }
        val candidates = cachedDevices.filter { it.displayId == displayId }
        if (candidates.size != 1) {
            cachedVersion = -1 // Let the next explicit call query again, even without another notification.
            throw PrivilegeEvdevTargetException(displayId, "Display $displayId requires one associated touch node; found ${candidates.size}")
        }
        version to candidates.single()
    }

    fun openTouchscreen(displayId: Int): Int {
        require(displayId >= 0) { "Expected a logical display ID" }
        val (version, selected) = selectDevice(displayId)
        val mapping = access.displayMapping(displayId)
        return open(selected.path, true, displayId, mapping, version, selected.inputDeviceId)
    }

    @Synchronized
    private fun open(path: String, writable: Boolean, displayId: Int? = null,
                     mapping: PrivilegeEvdevTouchMapping? = null, associationVersion: Long? = null,
                     inputDeviceId: Int? = null): Int = execute {
        check(!closed) { "Input controller is closed" }
        check(devices.size < 64) { "Too many open input devices" }
        check(nextHandle < Int.MAX_VALUE) { "Input handle space exhausted" }
        val device = Device(writable)
        device.displayId = displayId
        device.associationVersion = associationVersion
        device.inputDeviceId = inputDeviceId
        if (associationVersion != null) device.touchVersion = changes!!.touchVersion(checkNotNull(displayId), inputDeviceId)
        initialize(device, path, mapping, associationVersion)
        val handle = ++nextHandle
        devices[handle] = device
        handle
    }

    private fun initialize(device: Device, path: String, mapping: PrivilegeEvdevTouchMapping?, version: Long?) {
        require(devicePath.matches(path)) { "Expected /dev/input/eventN" }
        device.touch = null
        device.fd = native.open(path, device.writable)
        device.path = path
        device.released = false
        try {
            if (mapping != null) device.touch = PrivilegeEvdevTouch(native, device.fd, mapping)
            if (version != null && changes!!.version != version) {
                throw PrivilegeEvdevTargetException(checkNotNull(device.displayId), "Input configuration changed while opening touch device")
            }
        } catch (error: Throwable) {
            try { closeDevice(device) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    private fun <T> device(handle: Int, screen: Boolean = false, block: PrivilegeEvdevBackend.(Device) -> T): T = execute {
        val device = synchronized(this) {
            check(!closed) { "Input controller is closed" }
            checkNotNull(devices[handle]) { "Input device is closed" }
        }
        synchronized(device) {
            check(!closed && !device.closing) { "Input device is closed" }
            check(screen || device.associationVersion == null) { "Screen handles only support gestures" }
            native.block(device)
        }
    }
    fun configureTouch(handle: Int, mapping: PrivilegeEvdevTouchMapping) = device(handle) {
        check(it.writable) { "Device was opened read-only" }
        it.touch?.release()
        it.touch = PrivilegeEvdevTouch(this, it.fd, mapping)
        it.displayId = null // Explicit mapping disables automatic refresh for this handle.
    }
    fun touch(handle: Int, startX: Float, startY: Float, endX: Float, endY: Float,
              durationMillis: Long, holdMillis: Long, cancelled: () -> Boolean = { false }) = device(handle, screen = true) {
        val displayId = it.displayId
        val mapping = displayId?.let { id -> access.displayMapping(id) }
        val state = displayId?.let(access::displayState)
        if (it.associationVersion != null && (it.released || changes!!.version != it.associationVersion)) {
            val (version, selected) = selectDevice(checkNotNull(displayId))
            val touchVersion = changes!!.touchVersion(displayId, selected.inputDeviceId)
            if (it.released || it.path != selected.path || it.inputDeviceId != selected.inputDeviceId ||
                it.touchVersion != touchVersion) {
                // A relevant input/presence change can replace the device even at the same eventN.
                closeDevice(it)
                initialize(it, selected.path, mapping, version)
            }
            if (changes!!.version != version) throw PrivilegeEvdevTargetException(checkNotNull(displayId), "Input configuration changed while matching touch device")
            it.inputDeviceId = selected.inputDeviceId
            it.touchVersion = touchVersion
            it.associationVersion = version
        }
        if (mapping != null && it.touch?.mapping != mapping) {
            it.touch?.release()
            it.touch = PrivilegeEvdevTouch(this, it.fd, mapping)
        }
        checkNotNull(it.touch) { "Call configureTouch before touch gestures" }
            .gesture(startX, startY, endX, endY, durationMillis, holdMillis) {
                if (cancelled() || closed || it.closing) true else {
                    if (it.associationVersion != null && changes!!.touchVersion(checkNotNull(displayId), it.inputDeviceId) != it.touchVersion) {
                        throw PrivilegeEvdevTargetException(checkNotNull(displayId), "Input configuration changed during touch gesture")
                    }
                    if (displayId != null && (access.displayMapping(displayId) != mapping || access.displayState(displayId) != state)) {
                        throw PrivilegeEvdevTargetException(displayId, "Display $displayId changed during touch gesture")
                    }
                    false
                }
            }
    }
    fun queryName(handle: Int): String = device(handle) { name(it.fd).decodeToString() }
    fun queryCapabilities(handle: Int, type: Int): IntArray = device(handle) {
        require(type in 0..0x1f)
        bits(it.fd, type)
    }
    fun queryAxis(handle: Int, axis: Int): PrivilegeEvdevAxis = device(handle) {
        require(axis in 0..0x3f)
        val values = axis(it.fd, axis)
        PrivilegeEvdevAxis(values[0], values[1], values[2], values[3], values[4], values[5])
    }
    fun queryTrackingIds(handle: Int): IntArray = device(handle) { slots(it.fd) }
    fun writeEvents(handle: Int, events: IntArray): PrivilegeEvdevWriteResult = device(handle) {
        check(it.writable) { "Device was opened read-only" }
        require(events.size in 3..768 && events.size % 3 == 0) { "Expected 1..256 events" }
        for (index in events.indices step 3) {
            require(events[index] in 0..0x1f) { "Invalid Linux event type" }
            require(events[index + 1] in 0..0xffff) { "Invalid Linux event code" }
        }
        it.touch?.release()
        val result = write(it.fd, events)
        PrivilegeEvdevWriteResult(events.size / 3, result[0].takeIf { count -> count >= 0 },
            result[1].takeIf { errno -> errno != 0 })
    }
    fun closeDevice(handle: Int) {
        val device = synchronized(this) { devices[handle]?.also { it.closing = true } }
        if (device != null) {
            try { closeDevice(device) } finally { synchronized(this) { devices.remove(handle) } }
        }
    }
    private fun closeDevice(device: Device) = synchronized(device) {
        if (!device.released) {
            try { device.touch?.release() } finally {
                device.released = true
                native.close(device.fd)
            }
        }
    }
    override fun close(): Unit = synchronized(closeLock) {
        val handles = synchronized(this) {
            if (closed && changesClosed) return
            closed = true
            devices.values.toList().also { devices.clear(); it.forEach { device -> device.closing = true } }
        }
        var failure: Exception? = null
        try { changes?.close(); changesClosed = true } catch (error: Exception) { failure = error }
        try { owner?.unlinkToDeath(death, 0) } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        // Keep owner UID/PID pinned so a lost close reply can be explicitly retried.
        owner = null
        handles.forEach { try { closeDevice(it) } catch (error: Exception) { if (failure == null) failure = error } }
        failure?.let { throw it }
    }
}

/** Only the Binder boundary translates exceptions to wire results. */
internal class InputEndpoint(private val session: InputSession) : IPrivilegeInputDeviceController.Stub() {
    override fun openTouchscreen(displayId: Int) = remote(PrivilegeInputOpenResult()) {
        handle = session.openTouchscreen(displayId)
    }
    override fun attach(owner: IBinder) = result(PrivilegeInputStatusResult()) { session.attach(owner) }
    override fun queryTouchDevices() = remote(PrivilegeInputTouchDevicesResult()) { devices = session.queryTouchDevices() }
    override fun listDevices() = remote(PrivilegeInputDeviceListResult()) { paths = session.listDevices() }
    override fun openDevice(path: String, writable: Boolean) = remote(PrivilegeInputOpenResult()) {
        handle = session.openDevice(path, writable)
    }
    override fun openTouchDevice(path: String, displayId: Int) = remote(PrivilegeInputOpenResult()) {
        handle = session.openTouchDevice(path, displayId)
    }
    override fun configureTouch(device: Int, mapping: PrivilegeEvdevTouchMapping) = remote(PrivilegeInputStatusResult()) {
        session.configureTouch(device, mapping)
    }
    override fun createTouchCancellation(): IPrivilegeInputCancellation = session.remote { InputCancellation() }

    override fun touch(device: Int, startX: Float, startY: Float, endX: Float, endY: Float,
                       durationMillis: Long, holdMillis: Long, cancellation: IPrivilegeInputCancellation) = remote(PrivilegeInputStatusResult()) {
        val signal = requireNotNull(cancellation.asBinder() as? InputCancellation) { "Expected this process's cancellation token" }
        session.touch(device, startX, startY, endX, endY, durationMillis, holdMillis) { signal.cancelled }
    }
    override fun queryName(device: Int) = remote(PrivilegeInputNameResult()) {
        name = session.queryName(device)
    }
    override fun queryCapabilities(device: Int, type: Int) = remote(PrivilegeInputIntArrayResult()) {
        values = session.queryCapabilities(device, type)
    }
    override fun queryAxis(device: Int, axis: Int) = remote(PrivilegeInputAxisResult()) {
        val axisValue = session.queryAxis(device, axis)
        value = axisValue.value
        minimum = axisValue.minimum
        maximum = axisValue.maximum
        fuzz = axisValue.fuzz
        flat = axisValue.flat
        resolution = axisValue.resolution
    }
    override fun queryTrackingIds(device: Int) = remote(PrivilegeInputIntArrayResult()) {
        values = session.queryTrackingIds(device)
    }
    override fun writeEvents(device: Int, events: IntArray) = remote(WireWriteResult()) {
        val value = session.writeEvents(device, events)
        submittedEventCount = value.submittedEventCount ?: -1
        errno = value.errno ?: 0
    }
    override fun closeDevice(device: Int) = remote(PrivilegeInputStatusResult()) { session.closeDevice(device) }
    override fun close() = remote(PrivilegeInputStatusResult()) { session.close() }
    private fun <T : PrivilegeInputResult> remote(response: T, block: T.() -> Unit): T =
        session.remote { result(response, block) }

    private inline fun <T : PrivilegeInputResult> result(response: T, block: T.() -> Unit): T {
        try {
            response.block()
        } catch (error: PrivilegeEvdevTargetException) {
            response.error = PrivilegeInputError(PrivilegeInputError.TARGET, message = error.message, displayId = error.displayId)
        } catch (error: PrivilegeEvdevWriteException) {
            response.error = PrivilegeInputError(
                PrivilegeInputError.WRITE, errno = error.result.errno ?: 0,
                requestedEventCount = error.result.requestedEventCount,
                submittedEventCount = error.result.submittedEventCount,
            )
        } catch (error: IOException) {
            response.error = PrivilegeInputError(
                if (error is PrivilegeEvdevSyscallException) PrivilegeInputError.DEVICE else PrivilegeInputError.IO,
                if (error is PrivilegeEvdevSyscallException) error.errno else 0,
                error.message?.take(1024),
            )
        } catch (error: IllegalArgumentException) {
            response.error = PrivilegeInputError(PrivilegeInputError.ARGUMENT, message = error.message?.take(1024))
        } catch (error: IllegalStateException) {
            response.error = PrivilegeInputError(PrivilegeInputError.STATE, message = error.message?.take(1024))
        }
        return response
    }
}

private val devicePath = Regex("/dev/input/event[0-9]+")

private fun listInputDevices(): List<String> =
    (File("/dev/input").listFiles() ?: throw IOException("Cannot enumerate /dev/input"))
        .map { it.absolutePath }.filter(devicePath::matches).sorted()

/** Per-gesture signal; it never acquires the device lock. */
internal class InputCancellation : IPrivilegeInputCancellation.Stub() {
    @Volatile var cancelled = false
        private set
    override fun cancel() { cancelled = true }
}
