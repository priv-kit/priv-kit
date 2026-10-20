package priv.kit.evdev

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.PrivilegeEvdevBackend
import priv.kit.evdev.internal.PrivilegeEvdevTouch
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeEvdevTouchTest {
    private class Backend(val protocol: Int = 2) : PrivilegeEvdevBackend {
        val frames = mutableListOf<List<List<Int>>>()
        var failAxis = false
        var failFrame = -1
        var failedWriteResult = intArrayOf(-1, 5)
        var failRelease = false
        var activeSlots = intArrayOf(-1, -1)
        var opens = 0
        val closed = mutableListOf<Int>()
        var onWrite: ((Int) -> Unit)? = null
        override fun open(path: String, writable: Boolean) = ++opens
        override fun name(fd: Int) = "touch".encodeToByteArray()
        override fun bits(fd: Int, type: Int) = when (type) {
            1 -> intArrayOf(330, 325)
            3 -> when (protocol) {
                2 -> intArrayOf(47, 53, 54, 57, 58)
                1 -> intArrayOf(53, 54, 57, 58)
                else -> intArrayOf(0, 1, 24)
            }
            else -> intArrayOf(1, 3)
        }
        override fun axis(fd: Int, axis: Int) = if (failAxis) throw IOException("Axis unavailable") else when (axis) {
            0, 53 -> intArrayOf(0, 100, 1100, 0, 0, 0)
            1, 54 -> intArrayOf(0, 200, 2200, 0, 0, 0)
            57 -> intArrayOf(-1, 0, 65535, 0, 0, 0)
            else -> intArrayOf(0, 0, 100, 0, 0, 0)
        }
        override fun slots(fd: Int) = activeSlots
        override fun write(fd: Int, events: IntArray): IntArray {
            val frame = events.toList().chunked(3)
            frames += frame
            onWrite?.invoke(fd)
            return if (frames.size == failFrame || (failRelease && listOf(1, 330, 0) in frame))
                failedWriteResult else intArrayOf(events.size / 3, 0)
        }
        override fun close(fd: Int) { closed += fd }
    }
    private val mapping = PrivilegeEvdevTouchMapping(101, 201)
    private fun engine(backend: Backend, mapping: PrivilegeEvdevTouchMapping = this.mapping) =
        PrivilegeEvdevTouch(backend, 1, mapping)

    @Test fun typeBEmitsOneContactAndReleaseWithMappedEndpoints() {
        val backend = Backend()
        val times = mutableListOf<Long>()
        backend.onWrite = { times += System.nanoTime() }
        engine(backend).gesture(0f, 0f, 100f, 200f, 16, 0) { false }
        // Allow millisecond clock rounding, but reject an immediate endpoint.
        assertTrue(times[times.lastIndex - 1] - times.first() >= 15_000_000L)
        assertTrue(backend.frames.size >= 3)
        val down = backend.frames.first()
        assertEquals(listOf(1, 330, 1), down.first())
        assertTrue(listOf(3, 47, 0) in down)
        assertTrue(listOf(3, 57, 0) in down)
        assertTrue(listOf(3, 53, 100) in down)
        assertTrue(listOf(3, 54, 200) in down)
        assertTrue(listOf(3, 53, 1100) in backend.frames[backend.frames.lastIndex - 1])
        assertTrue(listOf(3, 54, 2200) in backend.frames[backend.frames.lastIndex - 1])
        assertTrue(listOf(3, 57, -1) in backend.frames.last())
        assertTrue(backend.frames.all { it.last() == listOf(0, 0, 0) })
    }

    @Test fun typeAAndSingleTouchUseTheirOwnContactProtocol() {
        for (protocol in listOf(0, 1)) {
            val backend = Backend(protocol)
            engine(backend).gesture(0f, 0f, 0f, 0f, 0, 0) { false }
            val down = backend.frames.first()
            val up = backend.frames.last()
            assertFalse(down.any { it[0] == 3 && it[1] == 47 })
            if (protocol == 1) {
                assertTrue(listOf(0, 2, 0) in down)
                assertTrue(listOf(0, 2, 0) in up)
            } else {
                assertTrue(listOf(3, 0, 100) in down)
                assertTrue(listOf(3, 1, 200) in down)
                assertTrue(listOf(3, 24, 0) in up)
            }
        }
    }

    @Test fun rotationSwapAndInversionMapScreenCorners() {
        val expected = listOf(Pair(100, 200), Pair(1100, 200), Pair(1100, 2200), Pair(100, 2200))
        for (rotation in 0..3) {
            val backend = Backend()
            engine(backend, mapping.copy(rotation = rotation)).gesture(0f, 0f, 0f, 0f, 0, 0) { false }
            assertTrue(listOf(3, 53, expected[rotation].first) in backend.frames[0])
            assertTrue(listOf(3, 54, expected[rotation].second) in backend.frames[0])
        }
        val backend = Backend()
        engine(backend, mapping.copy(swapAxes = true, invertX = true)).gesture(100f, 0f, 100f, 0f, 0, 0) { false }
        assertTrue(listOf(3, 53, 1100) in backend.frames[0])
        assertTrue(listOf(3, 54, 2200) in backend.frames[0])
    }

    @Test fun holdPrecedesMovementAndZeroDurationStillReachesEndpoint() {
        val backend = Backend()
        val times = mutableListOf<Long>()
        backend.onWrite = { times += android.os.SystemClock.uptimeMillis() }
        engine(backend).gesture(0f, 0f, 100f, 200f, 0, 500) { false }
        assertEquals(3, times.size)
        assertTrue(times[1] - times[0] >= 500)
        assertTrue(listOf(3, 53, 1100) in backend.frames[1])
    }

    @Test fun invalidArgumentsAndOccupiedSlotsProduceNoWrites() {
        val backend = Backend()
        val touch = engine(backend)
        for (point in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 101f)) {
            assertThrows(IllegalArgumentException::class.java) { touch.gesture(point, 0f, 0f, 0f, 0, 0) { false } }
        }
        assertThrows(IllegalArgumentException::class.java) { touch.gesture(0f, 0f, 0f, 0f, -1, 0) { false } }
        backend.activeSlots = intArrayOf(-1, 12)
        assertThrows(IllegalStateException::class.java) { touch.gesture(0f, 0f, 0f, 0f, 0, 0) { false } }
        assertTrue(backend.frames.isEmpty())
    }

    @Test fun partialDownIsNotReplayedAndReleaseFailureIsSuppressed() {
        val backend = Backend()
        backend.failFrame = 1
        backend.failRelease = true
        val touch = engine(backend)
        val failure = assertThrows(PrivilegeEvdevWriteException::class.java) { touch.gesture(0f, 0f, 0f, 0f, 0, 0) { false } }
        assertEquals(PrivilegeEvdevWriteResult(backend.frames.first().size, null, 5), failure.result)
        assertTrue(failure.suppressed.single() is PrivilegeEvdevWriteException)
        assertEquals(2, backend.frames.size)
        assertEquals(1, failure.suppressed.size)
        assertThrows(IllegalStateException::class.java) { touch.gesture(0f, 0f, 0f, 0f, 0, 0) { false } }
        backend.failRelease = false
        touch.release()
        assertEquals(3, backend.frames.size)
        touch.release()
        assertEquals(3, backend.frames.size)
    }

    @Test fun cancellationAndInterruptionReleaseContact() {
        val backend = Backend()
        assertThrows(IllegalStateException::class.java) {
            engine(backend).gesture(0f, 0f, 100f, 200f, 100, 0) { backend.frames.isNotEmpty() }
        }
        assertTrue(listOf(3, 57, -1) in backend.frames.last())
        val interrupted = Backend()
        interrupted.onWrite = { if (interrupted.frames.size == 1) Thread.currentThread().interrupt() }
        val touch = PrivilegeEvdevTouch(interrupted, 1, mapping)
        try {
            assertThrows(IOException::class.java) { touch.gesture(0f, 0f, 100f, 200f, 100, 0) { false } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertTrue(listOf(3, 57, -1) in interrupted.frames.last())
    }

    @Test fun remoteWriteFailureRetainsFrameResult() {
        val backend = Backend()
        PrivilegeEvdevController.createLocal(backend).use { local ->
            val bridge = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                    local.asBinder().transact(code, data, reply, flags)
            }
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                remote.openDevice("/dev/input/event0", true).use { device ->
                    device.configureTouch(mapping)
                    for (result in listOf(intArrayOf(-1, 5), intArrayOf(2, 5), intArrayOf(2, 0))) {
                        backend.failFrame = backend.frames.size + 1
                        backend.failedWriteResult = result
                        val before = backend.frames.size
                        val error = assertThrows(PrivilegeEvdevWriteException::class.java) {
                            kotlinx.coroutines.runBlocking { device.tap(0f, 0f) }
                        }
                        assertEquals(PrivilegeEvdevWriteResult(backend.frames[before].size, result[0].takeIf { it >= 0 }, result[1].takeIf { it != 0 }), error.result)
                        assertEquals(before + 2, backend.frames.size)
                    }
                }
            }
        }
    }

    @Test fun remoteGestureRunsOnceAndMappingSurvivesParcel() {
        val backend = Backend()
        PrivilegeEvdevController.createLocal(backend).use { local ->
            var lostReply = false
            val bridge = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    val result = local.asBinder().transact(code, data, reply, flags)
                    if (lostReply && backend.frames.isNotEmpty()) { lostReply = false; throw RemoteException("Lost reply") }
                    return result
                }
            }
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                remote.openDevice("/dev/input/event0", true).use { device ->
                    device.configureTouch(mapping.copy(rotation = 2))
                    lostReply = true
                    assertThrows(RemoteException::class.java) { kotlinx.coroutines.runBlocking { device.tap(0f, 0f) } }
                    assertEquals(2, backend.frames.size)
                    assertTrue(listOf(3, 53, 1100) in backend.frames[0])
                    assertTrue(listOf(3, 54, 2200) in backend.frames[0])
                    kotlinx.coroutines.runBlocking { device.swipe(0f, 0f, 100f, 200f, 0) }
                }
            }
        }
        assertEquals(listOf(1), backend.closed)
    }

    @Test fun coroutineCancellationReleasesLocalAndRemoteContacts() = kotlinx.coroutines.runBlocking {
        for (remote in listOf(false, true)) {
            val backend = Backend()
            val pressed = kotlinx.coroutines.CompletableDeferred<Unit>()
            backend.onWrite = { if (backend.frames.size == 1) pressed.complete(Unit) }
            val local = PrivilegeEvdevController.createLocal(backend)
            val input = if (remote) PrivilegeEvdevController.fromBinder(object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                    local.asBinder().transact(code, data, reply, flags)
            }) else local
            input.use {
                it.openDevice("/dev/input/event0", true).use { device ->
                    device.configureTouch(mapping)
                    val job = launch { device.dragAndDrop(0f, 0f, 100f, 200f, 5_000, 5_000) }
                    kotlinx.coroutines.withTimeout(5_000) {
                        pressed.await()
                        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
                        val queued = launch {
                            waiting.complete(Unit)
                            device.tap(20f, 20f)
                            fail("Queued gesture must be cancelled")
                        }
                        waiting.await()
                        queued.cancelAndJoin()
                        assertEquals(1, backend.frames.size)
                        job.cancelAndJoin()
                    }
                    assertTrue(job.isCancelled)
                    assertTrue(listOf(3, 57, -1) in backend.frames.last())
                    val count = backend.frames.size
                    backend.onWrite = null
                    device.tap(0f, 0f)
                    assertEquals(count + 2, backend.frames.size)
                }
            }
        }
    }

    @Test fun anotherDeviceRemainsUsableDuringLocalGesture() = verifyConcurrentClose(false)
    @Test fun anotherDeviceRemainsUsableDuringRemoteGesture() = verifyConcurrentClose(true)

    @Suppress("DEPRECATION")
    private fun verifyConcurrentClose(remote: Boolean) {
        val backend = Backend()
        val started = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val gestureThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        backend.onWrite = { fd ->
            gestureThread.set(Thread.currentThread())
            if (fd == 1 && backend.frames.size == 1) {
                started.countDown()
                check(allowWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val pool = Executors.newFixedThreadPool(3)
        val local = PrivilegeEvdevController.createLocal(backend)
        val input = if (remote) PrivilegeEvdevController.fromBinder(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                local.asBinder().transact(code, data, reply, flags)
        }) else local
        val closeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val closeStarted = CountDownLatch(1)
        try {
            val first = input.openDevice("/dev/input/event0", true)
            val other = input.openDevice("/dev/input/event1")
            first.configureTouch(mapping)
            val gesture = pool.submit<Boolean> {
                try { kotlinx.coroutines.runBlocking { first.dragAndDrop(0f, 0f, 100f, 200f, 100, 10000) }; false }
                catch (_: IllegalStateException) { true }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals("touch", pool.submit<String> { other.queryName() }.get(2, TimeUnit.SECONDS))
            val closing = pool.submit { closeThread.set(Thread.currentThread()); closeStarted.countDown(); input.close() }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            val threads = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var owner = -1L
            while (System.nanoTime() < deadline) {
                owner = threads.getThreadInfo(closeThread.get().id)?.lockOwnerId ?: -1
                if (owner == gestureThread.get().id) break
                Thread.yield()
            }
            assertEquals("Close must wait for the actual in-flight write", gestureThread.get().id, owner)
            assertFalse(closing.isDone)
            allowWrite.countDown()
            assertTrue(gesture.get(5, TimeUnit.SECONDS))
            closing.get(5, TimeUnit.SECONDS)
            assertTrue(listOf(3, 57, -1) in backend.frames.last())
            assertEquals(listOf(1, 2), backend.closed)
        } finally {
            allowWrite.countDown()
            input.close()
            local.close()
            pool.shutdownNow()
        }
    }
    @Test fun automaticMappingRefreshesAcrossBinderAndManualOverrideStopsRefresh() {
        val backend = Backend()
        var current = mapping
        var unavailable = false
        val requested = mutableListOf<Int>()
        PrivilegeEvdevController.createLocal(backend, displayMapping = { id ->
            requested += id
            if (unavailable) throw PrivilegeEvdevTargetException(0, "Display removed")
            current
        }).use { local ->
            val bridge = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                    local.asBinder().transact(code, data, reply, flags)
            }
            PrivilegeEvdevController.fromBinder(bridge).use { remote ->
                remote.openTouchDevice("/dev/input/event0", 7).use { device ->
                    assertTrue(device.writable)
                    kotlinx.coroutines.runBlocking { device.tap(100f, 200f) }
                    assertTrue(listOf(3, 53, 1100) in backend.frames[0])
                    current = PrivilegeEvdevTouchMapping(201, 101, rotation = 1)
                    kotlinx.coroutines.runBlocking { device.tap(200f, 0f) }
                    assertTrue(listOf(3, 53, 1100) in backend.frames[backend.frames.lastIndex - 1])
                    assertTrue(listOf(3, 54, 2200) in backend.frames[backend.frames.lastIndex - 1])
                    device.configureTouch(mapping.copy(invertX = true))
                    val reads = requested.size
                    unavailable = true
                    kotlinx.coroutines.runBlocking { device.tap(0f, 0f) }
                    assertEquals(reads, requested.size)
                    assertTrue(listOf(3, 53, 1100) in backend.frames[4])
                    assertTrue(listOf(3, 54, 200) in backend.frames[4])
                    assertTrue(requested.all { it == 7 })
                }
            }
        }
    }

    @Test fun automaticOpenRejectsMissingDisplayAndClosesFdOnProtocolFailure() {
        val backend = Backend()
        PrivilegeEvdevController.createLocal(backend).use { local ->
            assertThrows(IllegalStateException::class.java) { local.openTouchDevice("/dev/input/event0") }
            assertEquals(0, backend.opens)
        }
        PrivilegeEvdevController.createLocal(backend, displayMapping = {
            if (it != 0) throw PrivilegeEvdevTargetException(it, "Unknown display")
            mapping
        }).use { local ->
            assertThrows(IllegalArgumentException::class.java) { local.openTouchDevice("/dev/input/event0", -1) }
            assertThrows(PrivilegeEvdevTargetException::class.java) { local.openTouchDevice("/dev/input/event0", 99) }
            assertEquals(0, backend.opens)
            backend.failAxis = true
            assertThrows(IOException::class.java) { local.openTouchDevice("/dev/input/event0") }
            assertEquals(listOf(1), backend.closed)
        }
        assertEquals(listOf(1), backend.closed)
    }

    @Test fun displayChangeAndRemovalReleaseContactWithoutReplay() {
        for (removed in listOf(false, true)) {
            val backend = Backend()
            var changed = false
            PrivilegeEvdevController.createLocal(backend, displayMapping = {
                if (changed && removed) throw PrivilegeEvdevTargetException(0, "Display removed")
                if (changed) mapping.copy(rotation = 2) else mapping
            }).use { local ->
                local.openTouchDevice("/dev/input/event0").use { device ->
                    backend.onWrite = { changed = true }
                    assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { device.swipe(0f, 0f, 100f, 200f, 100) } }
                    assertEquals(2, backend.frames.size)
                    assertTrue(listOf(3, 57, -1) in backend.frames.last())
                    if (removed) {
                        assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { device.tap(0f, 0f) } }
                        assertEquals(2, backend.frames.size)
                    }
                }
            }
        }
    }

    @Test fun changedDisplayCannotDiscardFailedContactCleanup() {
        val backend = Backend()
        var current = mapping
        PrivilegeEvdevController.createLocal(backend, displayMapping = { current }).use { local ->
            local.openTouchDevice("/dev/input/event0").use { device ->
                backend.failRelease = true
                assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { device.tap(0f, 0f) } }
                current = mapping.copy(rotation = 2)
                assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { device.tap(0f, 0f) } }
                assertEquals(3, backend.frames.size)
                assertTrue(listOf(3, 57, -1) in backend.frames.last())
                backend.failRelease = false
            }
        }
        assertEquals(4, backend.frames.size)
        assertTrue(listOf(3, 57, -1) in backend.frames.last())
    }

}
