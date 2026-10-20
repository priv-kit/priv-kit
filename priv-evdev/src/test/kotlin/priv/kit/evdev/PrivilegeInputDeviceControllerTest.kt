package priv.kit.evdev

import priv.kit.evdev.internal.InputDeviceAccess

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.system.ErrnoException
import java.io.IOException
import priv.kit.evdev.internal.PrivilegeInputError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import priv.kit.evdev.internal.IPrivilegeInputDeviceController
import priv.kit.evdev.internal.PrivilegeEvdevBackend

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrivilegeInputDeviceControllerTest {

    private class Backend : PrivilegeEvdevBackend {
        var opens = 0
        var writes = 0
        var writeResult = intArrayOf(1, 11)
        var lastEvents: IntArray? = null
        var error: ErrnoException? = null
        val closed = mutableListOf<Int>()
        override fun open(path: String, writable: Boolean) = ++opens
        override fun name(fd: Int): ByteArray {
            error?.let { throw it }
            return "触摸屏".encodeToByteArray()
        }
        override fun bits(fd: Int, type: Int) = intArrayOf(0, 3)
        override fun axis(fd: Int, axis: Int) = intArrayOf(1, 0, 100, 0, 0, 1)
        override fun slots(fd: Int) = intArrayOf(-1, 42)
        override fun write(fd: Int, events: IntArray): IntArray {
            writes++
            lastEvents = events
            return writeResult
        }
        override fun close(fd: Int) { closed += fd }
    }

    private class Bridge(private val target: IBinder) : Binder() {
        var loseReply = false
        var dropRequest = false
        var calls = 0
        // No attached local interface: force generated AIDL Proxy and Parcel serialization.
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls++
            if (dropRequest) { dropRequest = false; throw RemoteException("Request not delivered") }
            val handled = target.transact(code, data, reply, flags)
            if (loseReply) { loseReply = false; throw RemoteException("Lost reply") }
            return handled
        }
    }

    private fun local(backend: Backend) = PrivilegeEvdevController.createLocal(
        backend,
        { listOf("/dev/input/event0") },
    )

    @Test fun listingAndClosingDoNotInitializeNativeBackend() {
        val access = object : InputDeviceAccess() {
            override val native: PrivilegeEvdevBackend get() = error("Native backend accessed")
            override fun list() = listOf("/dev/input/event0")
        }
        PrivilegeEvdevController.createLocal(access).use {
            assertEquals(listOf("/dev/input/event0"), it.listDevices())
        }
    }

    @Test fun controllerCloseCanRetryUndeliveredRequest() = verifyCloseRetry(true, false)
    @Test fun controllerCloseCanRetryLostReply() = verifyCloseRetry(true, true)
    @Test fun deviceCloseCanRetryUndeliveredRequest() = verifyCloseRetry(false, false)
    @Test fun deviceCloseCanRetryLostReply() = verifyCloseRetry(false, true)

    private fun verifyCloseRetry(closeController: Boolean, loseReply: Boolean) {
        val backend = Backend()
        local(backend).use { local ->
            val bridge = Bridge(local.asBinder())
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                val device = remote.openDevice("/dev/input/event0", true)
                val close = { if (closeController) remote.close() else device.close() }
                bridge.loseReply = loseReply
                bridge.dropRequest = !loseReply
                val calls = bridge.calls
                assertThrows(RemoteException::class.java) { close() }
                assertEquals(calls + 1, bridge.calls) // No automatic retry.
                assertEquals(if (loseReply) listOf(1) else emptyList<Int>(), backend.closed)
                assertThrows(IllegalStateException::class.java) { device.queryName() }
                assertThrows(IllegalStateException::class.java) { device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0))) }
                if (closeController) assertThrows(IllegalStateException::class.java) { remote.listDevices() }
                assertEquals(calls + 1, bridge.calls) // Failed close never re-enables normal operations.
                close()
                assertEquals(calls + 2, bridge.calls)
                close()
                device.close()
                assertEquals(calls + 2, bridge.calls)
                assertEquals(listOf(1), backend.closed)
                assertEquals(0, backend.writes)
            }
        }
    }

    @Test fun deviceCleanupStillWorksAfterControllerCloseFailure() {
        val backend = Backend()
        local(backend).use { local ->
            val bridge = Bridge(local.asBinder())
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                val device = remote.openDevice("/dev/input/event0")
                bridge.dropRequest = true
                assertThrows(RemoteException::class.java) { remote.close() }
                device.close()
                assertEquals(listOf(1), backend.closed)
                remote.close()
                assertEquals(listOf(1), backend.closed)
            }
        }
    }

    @Test fun localControllerPreservesCauseEvenInsideAnotherBinderCall() {
        val backend = Backend()
        val cause = ErrnoException("EVIOCGNAME", 13)
        local(backend).use { input ->
            try {
                ShadowBinder.setCallingUid(12345)
                ShadowBinder.setCallingPid(android.os.Process.myPid() + 100)
                input.openDevice("/dev/input/event0").use { device ->
                    backend.error = cause
                    val failure = assertThrows(PrivilegeEvdevSyscallException::class.java) { device.queryName() }
                    assertSame(cause, failure.cause)
                    backend.error = null
                    assertEquals("触摸屏", device.queryName())
                    assertEquals(PrivilegeEvdevAxis(1, 0, 100, 0, 0, 1), device.queryAxis(0))
                    assertEquals(listOf(-1, 42), device.queryTrackingIds())
                }
            } finally {
                ShadowBinder.reset()
            }
        }
        val failure = IOException("original directory error")
        PrivilegeEvdevController.createLocal(
            backend,
            { throw failure },
        ).use { input ->
            assertSame(failure, assertThrows(IOException::class.java) { input.listDevices() })
        }
    }

    @Test fun endpointValidatesPackedEventsWithoutRepacking() {
        val backend = Backend()
        local(backend).use { input ->
            val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(input.asBinder())
            val handle = endpoint.openDevice("/dev/input/event0", true).handle
            for (type in listOf(-1, 32)) {
                assertEquals(PrivilegeInputError.ARGUMENT, endpoint.queryCapabilities(handle, type).error!!.code)
            }
            for (axis in listOf(-1, 64)) {
                assertEquals(PrivilegeInputError.ARGUMENT, endpoint.queryAxis(handle, axis).error!!.code)
            }
            val events = intArrayOf(1, 30, 1, 0, 0, 0)
            assertNull(endpoint.writeEvents(handle, events).error)
            assertSame(events, backend.lastEvents)
            assertEquals(1, backend.writes)
            for (invalid in listOf(intArrayOf(), intArrayOf(1), IntArray(771),
                intArrayOf(32, 0, 0), intArrayOf(1, -1, 0), intArrayOf(1, 65536, 0))) {
                assertEquals(PrivilegeInputError.ARGUMENT, endpoint.writeEvents(handle, invalid).error!!.code)
            }
            val readOnly = endpoint.openDevice("/dev/input/event0", false).handle
            assertEquals(PrivilegeInputError.STATE, endpoint.writeEvents(readOnly, events).error!!.code)
            assertEquals(1, backend.writes)
        }
    }

    @Test fun proxyRoundTripAndSessionClose() {
        val backend = Backend()
        val local = local(backend)
        val remote = PrivilegeEvdevController.fromBinder(Bridge(local.asBinder()))
        assertEquals(listOf("/dev/input/event0"), remote.listDevices())
        val device = remote.openDevice("/dev/input/event0", true)
        assertEquals("触摸屏", device.queryName())
        assertEquals(setOf(0, 3), device.queryCapabilities())
        assertEquals(PrivilegeEvdevAxis(1, 0, 100, 0, 0, 1), device.queryAxis(0))
        assertEquals(listOf(-1, 42), device.queryTrackingIds())
        val result = device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0)))
        assertEquals(1, result.submittedEventCount)
        assertEquals(11, result.errno)
        remote.close()
        remote.close()
        device.close()
        local.close()
        assertEquals(listOf(1), backend.closed)
        assertThrows(IllegalStateException::class.java) { device.queryName() }
    }

    @Test fun remoteErrorsPreserveErrnoAndRemainUsable() {
        val backend = Backend()
        local(backend).use { local ->
            PrivilegeEvdevController.fromBinder(Bridge(local.asBinder())).use { remote ->
                remote.openDevice("/dev/input/event0").use { device ->
                    backend.error = ErrnoException("ioctl", 13)
                    assertEquals(13, assertThrows(PrivilegeEvdevSyscallException::class.java) { device.queryName() }.errno)
                    backend.error = null
                    assertEquals("触摸屏", device.queryName())
                    assertThrows(IllegalStateException::class.java) { device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0))) }
                }
            }
        }
    }

    @Test fun lostWriteReplyIsNotRetried() {
        val backend = Backend()
        local(backend).use { local ->
            val bridge = Bridge(local.asBinder())
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                remote.openDevice("/dev/input/event0", true).use { device ->
                    bridge.loseReply = true
                    assertThrows(RemoteException::class.java) { device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0))) }
                    assertEquals(1, backend.writes)
                }
            }
        }
    }

    @Test fun ownerDeathClosesEveryHandleAndPreventsReuse() {
        val backend = Backend()
        val local = local(backend)
        val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(Bridge(local.asBinder()))
        var death: IBinder.DeathRecipient? = null
        val token = object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
        }
        assertEquals(0, endpoint.attach(token).error?.code ?: 0)
        val first = local.openDevice("/dev/input/event0")
        local.openDevice("/dev/input/event1")
        death!!.binderDied()
        assertEquals(listOf(1, 2), backend.closed)
        assertThrows(IllegalStateException::class.java) { first.queryName() }
        assertThrows(IllegalStateException::class.java) { local.openDevice("/dev/input/event0") }
        first.close()
        local.close()
        assertEquals(2, backend.closed.size)
    }

    @Test fun rejectsSecondOwnerAndEnforcesServerLimits() {
        val backend = Backend()
        local(backend).use { local ->
            PrivilegeEvdevController.fromBinder(Bridge(local.asBinder())).use { remote ->
                assertThrows(IllegalStateException::class.java) { PrivilegeEvdevController.fromBinder(local.asBinder()) }
                val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(Bridge(local.asBinder()))
                val handle = endpoint.openDevice("/dev/input/event0", true).handle
                assertEquals(3, endpoint.writeEvents(handle, IntArray(771)).error?.code ?: 0)
                assertEquals(3, endpoint.writeEvents(handle, intArrayOf(99, 0, 0)).error?.code ?: 0)
                assertEquals(0, backend.writes)
                repeat(63) { remote.openDevice("/dev/input/event0") }
                assertThrows(IllegalStateException::class.java) { remote.openDevice("/dev/input/event0") }
                assertEquals(64, backend.opens)
            }
        }
        assertEquals(64, backend.closed.size)
    }

    @Test fun callerUidAndPidMustMatchAttachedOwner() {
        val backend = Backend()
        val local = local(backend)
        val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(Bridge(local.asBinder()))
        try {
            ShadowBinder.setCallingPid(android.os.Process.myPid() + 100)
            ShadowBinder.setCallingUid(12345)
            assertThrows(SecurityException::class.java) { endpoint.listDevices() }
            endpoint.attach(Binder())
            assertEquals(0, endpoint.listDevices().error?.code ?: 0)
            ShadowBinder.setCallingUid(54321)
            assertThrows(SecurityException::class.java) { endpoint.listDevices() }
            ShadowBinder.setCallingUid(12345)
            ShadowBinder.setCallingPid(android.os.Process.myPid() + 101)
            assertThrows(SecurityException::class.java) { endpoint.close() }
        } finally {
            ShadowBinder.reset()
            local.close()
        }
    }

    @Test fun deathUnregistrationFailureStillClosesDevices() {
        val backend = Backend()
        val local = local(backend)
        val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(Bridge(local.asBinder()))
        endpoint.attach(object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
                throw IllegalStateException("Already detached")
            }
        })
        local.openDevice("/dev/input/event0")
        local.openDevice("/dev/input/event1")
        assertThrows(IllegalStateException::class.java) { local.close() }
        assertEquals(listOf(1, 2), backend.closed)
        local.close()
    }

    @Test fun failureResultsRoundTripWithTypedErrorFields() {
        val backend = Backend()
        val local = PrivilegeEvdevController.createLocal(
            backend,
            { throw IOException("Cannot enumerate devices") },
        )
        local.use {
            PrivilegeEvdevController.fromBinder(Bridge(local.asBinder())).use { remote ->
                val failure = assertThrows(IOException::class.java) { remote.listDevices() }
                assertEquals("Cannot enumerate devices", failure.message)
                val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(Bridge(local.asBinder()))
                assertEquals(PrivilegeInputError.ARGUMENT, endpoint.openDevice("/bad", false).error!!.code)
                val results = listOf(
                    endpoint.attach(Binder()),
                    endpoint.queryName(999),
                    endpoint.queryCapabilities(999, 0),
                    endpoint.queryAxis(999, 0),
                    endpoint.queryTrackingIds(999),
                    endpoint.writeEvents(999, intArrayOf(0, 0, 0)),
                )
                results.forEach { result ->
                    assertEquals(PrivilegeInputError.STATE, result.error!!.code)
                    assertEquals(0, result.error!!.errno)
                    assertFalse(result.error!!.message.isNullOrEmpty())
                }
                remote.openDevice("/dev/input/event0", true).use { device ->
                    backend.writeResult = intArrayOf(-1, 11)
                    val unknown = device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0)))
                    assertNull(unknown.submittedEventCount)
                    assertEquals(11, unknown.errno)
                    assertFalse(unknown.isComplete)
                    backend.writeResult = intArrayOf(1, 0)
                    assertTrue(device.writeEvents(listOf(PrivilegeEvdevEvent(0, 0, 0))).isComplete)
                }
            }
        }
    }
}
