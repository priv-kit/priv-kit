package priv.kit.core.internal.file

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PrivilegeFileSystemBinderTest {
    @Test
    fun walkHasFourActiveSlotsWithoutAQueueAndForwardsDepth() = runBlocking {
        val startedCount = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = LinkedBlockingQueue<Triple<String, Int, List<String>>>()
        val sources = mutableListOf<ParcelFileDescriptor>()
        val binder = PrivilegeFileSystemBinder(
            walkAction = { path, maxDepth, skipDirectoryGlobs, _ ->
                received += Triple(path, maxDepth, skipDirectoryGlobs)
                if (
                    startedCount.incrementAndGet() ==
                    PrivilegeFileSystemContract.MAX_CONCURRENT_WALKS
                ) {
                    allStarted.complete(Unit)
                }
                release.await()
            },
            walkDispatcher = Dispatchers.IO,
        )
        try {
            repeat(PrivilegeFileSystemContract.MAX_CONCURRENT_WALKS) { index ->
                val pipe = ParcelFileDescriptor.createPipe()
                sources += pipe[0]
                assertEquals(
                    0,
                    binder.walk(
                        "/tree-$index",
                        index + 1,
                        arrayOf("skip-$index"),
                        pipe[1],
                    ),
                )
            }
            withTimeout(TEST_TIMEOUT_MILLIS) { allStarted.await() }

            val rejectedPipe = ParcelFileDescriptor.createPipe()
            sources += rejectedPipe[0]
            assertEquals(
                OsConstants.EBUSY,
                binder.walk("/tree-over-capacity", 1, emptyArray(), rejectedPipe[1]),
            )
            assertEquals(
                setOf(
                    Triple("/tree-0", 1, listOf("skip-0")),
                    Triple("/tree-1", 2, listOf("skip-1")),
                    Triple("/tree-2", 3, listOf("skip-2")),
                    Triple("/tree-3", 4, listOf("skip-3")),
                ),
                List(PrivilegeFileSystemContract.MAX_CONCURRENT_WALKS) {
                    requireNotNull(received.poll(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                }.toSet(),
            )
        } finally {
            release.complete(Unit)
            sources.forEach { source -> runCatching(source::close) }
            binder.shutdown()
        }
    }

    @Test
    fun walkRejectsNonPositiveDepthBeforeStarting() {
        val pipe = ParcelFileDescriptor.createPipe()
        val binder = PrivilegeFileSystemBinder()
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                binder.walk("/tree", 0, emptyArray(), pipe[1])
            }
        } finally {
            pipe.forEach { descriptor -> runCatching(descriptor::close) }
            binder.shutdown()
        }
    }

    @Test
    fun walkRejectsInvalidDirectoryNameGlobsBeforeStarting() {
        val pipe = ParcelFileDescriptor.createPipe()
        val binder = PrivilegeFileSystemBinder()
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                binder.walk("/tree", 1, arrayOf("parent/child"), pipe[1])
            }
        } finally {
            pipe.forEach { descriptor -> runCatching(descriptor::close) }
            binder.shutdown()
        }
    }

    @Test
    fun recursiveDeletePassesTargetUnchangedToOperation() = runBlocking {
        val receivedPath = CompletableDeferred<String>()
        val callback = ResultCallback()
        val binder = PrivilegeFileSystemBinder(
            recursiveDeleteAction = { path ->
                receivedPath.complete(path)
                true
            },
            recursiveDeleteDispatcher = Dispatchers.IO,
        )
        try {
            assertTrue(
                binder.startDeleteRecursively(
                    "raw-operation",
                    "/tree/./branch/../target",
                    callback,
                ),
            )
            assertEquals(
                "/tree/./branch/../target",
                withTimeout(TEST_TIMEOUT_MILLIS) { receivedPath.await() },
            )
            assertEquals(
                PrivilegeFileSystemContract.RECURSIVE_DELETE_SUCCEEDED,
                callback.await(),
            )

            Unit
        } finally {
            binder.shutdown()
        }
    }

    @Test
    fun recursiveDeleteReturnsAfterAdmissionAndDeliversAsyncResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callback = ResultCallback()
        val binder = PrivilegeFileSystemBinder(
            recursiveDeleteAction = {
                started.complete(Unit)
                release.await()
                true
            },
            recursiveDeleteDispatcher = Dispatchers.IO,
        )
        try {
            assertTrue(binder.startDeleteRecursively("operation", "/tree", callback))
            withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }
            assertNull(callback.results.poll(100, TimeUnit.MILLISECONDS))

            release.complete(Unit)
            assertEquals(
                PrivilegeFileSystemContract.RECURSIVE_DELETE_SUCCEEDED,
                callback.await(),
            )
        } finally {
            release.complete(Unit)
            binder.shutdown()
        }
    }

    @Test
    fun recursiveDeleteCancellationStopsJobWithoutSendingResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val callback = ResultCallback()
        val binder = PrivilegeFileSystemBinder(
            recursiveDeleteAction = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
            recursiveDeleteDispatcher = Dispatchers.IO,
        )
        try {
            assertTrue(binder.startDeleteRecursively("operation", "/tree", callback))
            withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }

            binder.cancelDeleteRecursively("operation")

            withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }
            assertNull(callback.results.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            binder.shutdown()
        }
    }

    @Test
    fun recursiveDeleteHasFourActiveSlotsWithoutAQueue() = runBlocking {
        val startedCount = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callbacks = List(PrivilegeFileSystemContract.MAX_CONCURRENT_RECURSIVE_DELETES) {
            ResultCallback()
        }
        val binder = PrivilegeFileSystemBinder(
            recursiveDeleteAction = {
                if (
                    startedCount.incrementAndGet() ==
                    PrivilegeFileSystemContract.MAX_CONCURRENT_RECURSIVE_DELETES
                ) {
                    allStarted.complete(Unit)
                }
                release.await()
                true
            },
            recursiveDeleteDispatcher = Dispatchers.IO,
        )
        try {
            callbacks.forEachIndexed { index, callback ->
                assertTrue(
                    binder.startDeleteRecursively(
                        "operation-$index",
                        "/tree-$index",
                        callback,
                    ),
                )
            }
            withTimeout(TEST_TIMEOUT_MILLIS) { allStarted.await() }

            assertFalse(
                binder.startDeleteRecursively(
                    "operation-over-capacity",
                    "/tree-over-capacity",
                    ResultCallback(),
                ),
            )
            assertFalse(
                binder.startDeleteRecursively(
                    "operation-0",
                    "/duplicate-id",
                    ResultCallback(),
                ),
            )

            release.complete(Unit)
            callbacks.forEach { callback ->
                assertEquals(
                    PrivilegeFileSystemContract.RECURSIVE_DELETE_SUCCEEDED,
                    callback.await(),
                )
            }
        } finally {
            release.complete(Unit)
            binder.shutdown()
        }
    }

    private class ResultCallback : ResultReceiver(null) {
        val results = LinkedBlockingQueue<Int>()

        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            results += resultCode
        }

        fun await(): Int = requireNotNull(
            results.poll(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        ) { "Timed out waiting for recursive delete result" }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS: Long = 5_000L
    }
}
