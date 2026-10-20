package priv.kit.evdev

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.InputChanges
import priv.kit.evdev.internal.PrivilegeEvdevBackend
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class PrivilegeInputTouchscreenTest {
    private class Changes : InputChanges {
        @Volatile override var version = 0L
        @Volatile var relevantVersion: Long? = null
        override fun touchVersion(displayId: Int, inputDeviceId: Int?) = relevantVersion ?: version
        var closes = 0
        var failClose = false
        var onClose: () -> Unit = {}
        override fun close() { closes++; onClose(); if (failClose) throw IOException("Listener cleanup failed") }
    }
    private class Backend : PrivilegeEvdevBackend {
        val paths = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        val frames = mutableListOf<Pair<Int, List<List<Int>>>>()
        var onWrite: () -> Unit = {}
        var onOpen: () -> Unit = {}
        var failOpen = false
        override fun open(path: String, writable: Boolean): Int {
            if (failOpen) throw IOException("Open failed")
            paths += path
            onOpen()
            return paths.size
        }
        override fun name(fd: Int) = "touch".encodeToByteArray()
        override fun bits(fd: Int, type: Int) = if (type == 1) intArrayOf(330) else intArrayOf(0, 1)
        override fun axis(fd: Int, axis: Int) = intArrayOf(0, 0, 1000, 0, 0, 0)
        override fun slots(fd: Int) = intArrayOf(-1)
        override fun write(fd: Int, events: IntArray): IntArray {
            check(fd !in closed)
            frames += fd to events.toList().chunked(3)
            onWrite()
            return intArrayOf(events.size / 3, 0)
        }
        override fun close(fd: Int) { check(fd !in closed); closed += fd }
    }
    private val backend = Backend()
    private val changes = Changes()
    private var snapshots = listOf(node(0))
    private var queries = 0
    private var registrations = 0
    private var onQuery: () -> Unit = {}
    private var state = android.view.Display.STATE_ON
    private fun node(n: Int, display: Int? = 0) = PrivilegeEvdevTouchDeviceInfo("/dev/input/event$n", "touch", n, display)
    private fun controller() = PrivilegeEvdevController.createLocal(
        backend, displayMapping = { PrivilegeEvdevTouchMapping(101, 101) },
        touchDevices = { queries++; onQuery(); snapshots },
        startChanges = { registrations++; changes },
        displayState = { state },
    )
    private fun proxy(binder: IBinder) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
            binder.transact(code, data, reply, flags)
    }

    @Test fun cachesAcrossHandlesAndRematchesThroughAidl() {
        controller().use { local ->
            PrivilegeEvdevController.fromBinder(proxy(local.asBinder())).use { input ->
                input.openTouchDevice().use { screen ->
                    assertEquals(0, screen.displayId)
                    repeat(3) { kotlinx.coroutines.runBlocking { screen.tap(10f, 20f) } }
                    input.openTouchDevice().use { kotlinx.coroutines.runBlocking { it.tap(10f, 20f) } }
                    assertEquals(1, queries)
                    assertEquals(1, registrations)
                    snapshots = listOf(node(8))
                    changes.version++
                    kotlinx.coroutines.runBlocking { screen.swipe(10f, 20f, 30f, 40f, 0) }
                    assertEquals(2, queries)
                    assertEquals(listOf("/dev/input/event0", "/dev/input/event0", "/dev/input/event8"), backend.paths)
                    assertTrue(1 in backend.closed)
                    assertEquals(3, backend.frames.last().first)
                }
            }
        }
        assertEquals(1, changes.closes)
        assertEquals(listOf(1, 2, 3), backend.closed.sorted())
    }

    @Test fun samePathReopensAfterNotificationButFixedNodeDoesNot() {
        controller().use { input ->
            input.openTouchDevice("/dev/input/event5", 0).use { fixed ->
                input.openTouchDevice().use { screen ->
                    changes.version++
                    kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                    kotlinx.coroutines.runBlocking { fixed.tap(0f, 0f) }
                    assertEquals(listOf("/dev/input/event5", "/dev/input/event0", "/dev/input/event0"), backend.paths)
                    assertEquals(listOf(2), backend.closed)
                }
            }
        }
    }

    @Test fun remoteTargetFailureRetainsDisplayId() {
        controller().use { local ->
            PrivilegeEvdevController.fromBinder(proxy(local.asBinder())).use { remote ->
                snapshots = emptyList()
                val error = assertThrows(PrivilegeEvdevTargetException::class.java) { remote.openTouchDevice(7) }
                assertEquals(7, error.displayId)
                assertTrue(backend.frames.isEmpty())
            }
        }
    }

    @Test fun missingAmbiguousAndUnknownAssociationsNeverWriteAndCanRecover() {
        controller().use { input ->
            input.openTouchDevice().use { screen ->
                for (nodes in listOf(emptyList(), listOf(node(1), node(2)), listOf(node(3, null)))) {
                    snapshots = nodes
                    changes.version++
                    assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) } }
                    assertTrue(backend.frames.isEmpty())
                }
                snapshots = listOf(node(4))
                changes.version++
                kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                assertEquals("/dev/input/event4", backend.paths.last())
            }
        }
    }

    @Test fun changedSnapshotIsNotCachedAndChangedOpenClosesFd() {
        controller().use { input ->
            onQuery = { changes.version++ }
            assertThrows(PrivilegeEvdevTargetException::class.java) { input.openTouchDevice() }
            assertTrue(backend.paths.isEmpty())
            onQuery = {}
            backend.onOpen = { changes.version++ }
            assertThrows(PrivilegeEvdevTargetException::class.java) { input.openTouchDevice() }
            assertEquals(listOf(1), backend.closed)
            backend.onOpen = {}
            input.openTouchDevice().use { kotlinx.coroutines.runBlocking { it.tap(0f, 0f) } }
            assertEquals(3, queries)
        }
    }

    @Test fun changeDuringGestureReleasesOldContactAndNextGestureRematches() {
        controller().use { input ->
            input.openTouchDevice().use { screen ->
                backend.onWrite = {
                    backend.onWrite = {}
                    snapshots = listOf(node(9))
                    changes.version++
                }
                assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { screen.swipe(0f, 0f, 50f, 50f, 1000) } }
                assertEquals(2, backend.frames.size) // One DOWN and its cleanup, no replay/MOVE.
                assertTrue(backend.frames.all { it.first == 1 })
                assertTrue(listOf(1, 330, 0) in backend.frames.last().second)
                kotlinx.coroutines.runBlocking { screen.tap(50f, 50f) }
                assertEquals(2, backend.frames.last().first)
            }
        }
    }

    @Test fun failedReplacementOpenRemainsRetryableWithoutDoubleClose() {
        controller().use { input ->
            input.openTouchDevice().use { screen ->
                changes.version++
                backend.failOpen = true
                assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) } }
                assertEquals(listOf(1), backend.closed)
                backend.failOpen = false
                kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                assertEquals(2, backend.frames.last().first)
            }
        }
        assertEquals(listOf(1, 2), backend.closed)
    }

    @Test fun lazyListenerAndOwnerDeathCleanup() {
        val input = controller()
        input.openTouchDevice("/dev/input/event0").close()
        assertEquals(0, registrations)
        val endpoint = priv.kit.evdev.internal.IPrivilegeInputDeviceController.Stub.asInterface(input.asBinder())
        var death: IBinder.DeathRecipient? = null
        endpoint.attach(object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
        })
        val screen = input.openTouchDevice()
        death!!.binderDied()
        assertEquals(1, changes.closes)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) } }
        screen.close()
        input.close()
        assertEquals(1, changes.closes)
        assertEquals(listOf(1, 2), backend.closed)
    }

    @Test fun transientMissingAssociationCanRecoverWithoutAnotherNotification() {
        controller().use { input ->
            snapshots = emptyList()
            assertThrows(PrivilegeEvdevTargetException::class.java) { input.openTouchDevice() }
            snapshots = listOf(node(0))
            input.openTouchDevice().use { screen ->
                assertEquals(2, queries)
                snapshots = listOf(node(1), node(2))
                changes.version++
                assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) } }
                snapshots = listOf(node(3))
                kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                assertEquals(4, queries)
                assertEquals("/dev/input/event3", backend.paths.last())
            }
        }
    }

    @Test fun failedListenerCloseIsRetriedThroughBinderWithoutReclosingDevices() {
        controller().use { local ->
            val input = PrivilegeEvdevController.fromBinder(proxy(local.asBinder()))
            input.openTouchDevice()
            changes.failClose = true
            assertThrows(IOException::class.java) { input.close() }
            assertEquals(listOf(1), backend.closed)
            assertEquals(1, changes.closes)
            assertThrows(IllegalStateException::class.java) { input.openTouchDevice() }
            changes.failClose = false
            input.close()
            input.close()
            assertEquals(2, changes.closes)
            assertEquals(listOf(1), backend.closed)
        }
        assertEquals(2, changes.closes)
    }

    @Test fun unrelatedNotificationDoesNotAbortOrReopenSelectedDevice() {
        changes.relevantVersion = 0
        controller().use { input ->
            input.openTouchDevice().use { screen ->
                backend.onWrite = { changes.version++ }
                kotlinx.coroutines.runBlocking { screen.swipe(0f, 0f, 50f, 50f, 0) }
                assertEquals(1, queries) // No dump while a contact is down.
                backend.onWrite = {}
                kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                assertEquals(2, queries)
                assertEquals(1, backend.paths.size)
                assertTrue(backend.closed.isEmpty())
            }
        }
    }

    @Test fun displayStateChangeAloneAbortsAndReleasesContact() {
        changes.relevantVersion = 0
        controller().use { input ->
            input.openTouchDevice().use { screen ->
                backend.onWrite = {
                    backend.onWrite = {}
                    state = android.view.Display.STATE_OFF
                    changes.version++
                }
                assertThrows(PrivilegeEvdevTargetException::class.java) { kotlinx.coroutines.runBlocking { screen.swipe(0f, 0f, 50f, 50f, 1000) } }
                assertEquals(2, backend.frames.size)
                assertTrue(listOf(1, 330, 0) in backend.frames.last().second)
                state = android.view.Display.STATE_ON
                kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) }
                assertEquals(1, backend.paths.size)
            }
        }
    }

    @Test fun closeDuringRematchWaitsForIoAndLeavesNoOpenDescriptor() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val closing = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val input = controller()
        try {
            val screen = input.openTouchDevice()
            changes.version++
            changes.onClose = { closing.countDown() }
            onQuery = {
                entered.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            val gesture = workers.submit<Unit> { kotlinx.coroutines.runBlocking { screen.tap(0f, 0f) } }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val close = workers.submit<Unit> { input.close() }
            assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                close.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            resume.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                gesture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            close.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(backend.frames.isEmpty())
            assertEquals(backend.paths.size, backend.closed.size)
        } finally {
            resume.countDown()
            workers.shutdownNow()
            input.close()
        }
    }
}
