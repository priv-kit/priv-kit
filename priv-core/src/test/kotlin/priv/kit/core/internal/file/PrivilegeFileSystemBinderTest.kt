package priv.kit.core.internal.file

import android.os.Bundle
import android.os.ResultReceiver
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
