package priv.kit.core.internal.file

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.binder.PrivilegeServerUnavailableException
import priv.kit.core.testing.TestBinder
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeFileSystemClientTest {
    @Test
    fun recursiveDeleteDecodesSynchronousSuccessAndFailureCallbacks() = runBlocking {
        listOf(
            PrivilegeFileSystemContract.RECURSIVE_DELETE_SUCCEEDED to true,
            PrivilegeFileSystemContract.RECURSIVE_DELETE_FAILED to false,
        ).forEach { (resultCode, expected) ->
            val fileSystem = RecordingFileSystem().apply {
                immediateResultCode = resultCode
            }

            assertEquals(
                expected,
                PrivilegeFileSystemClient.awaitRecursiveDelete("/tree") { block ->
                    block(fileSystem)
                },
            )
            assertEquals(0, fileSystem.binder.deathRecipientCount)
        }
    }

    @Test
    fun recursiveDeleteReturnsFalseWhenServerRejectsAdmission() = runBlocking {
        val fileSystem = RecordingFileSystem().apply { acceptsOperation = false }

        assertFalse(
            PrivilegeFileSystemClient.awaitRecursiveDelete("/tree") { block ->
                block(fileSystem)
            },
        )
        assertEquals(0, fileSystem.binder.deathRecipientCount)
    }

    @Test
    fun recursiveDeleteCancellationCancelsAcceptedServerOperation() = runBlocking {
        val fileSystem = RecordingFileSystem()
        val result = async {
            PrivilegeFileSystemClient.awaitRecursiveDelete("/tree") { block ->
                block(fileSystem)
            }
        }
        val operationId = withTimeout(TEST_TIMEOUT_MILLIS) {
            fileSystem.started.await()
        }

        result.cancelAndJoin()

        assertEquals(listOf(operationId), fileSystem.cancelledOperationIds)
        assertEquals(0, fileSystem.binder.deathRecipientCount)
    }

    @Test
    fun recursiveDeleteFailsWhenFileSystemBinderDiesWhilePending() = runBlocking {
        val fileSystem = RecordingFileSystem()
        val result = async {
            runCatching {
                PrivilegeFileSystemClient.awaitRecursiveDelete("/tree") { block ->
                    block(fileSystem)
                }
            }.exceptionOrNull()
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { fileSystem.started.await() }

        fileSystem.binder.killBinder(notifyDeathRecipients = true)

        val failure = withTimeout(TEST_TIMEOUT_MILLIS) { result.await() }
        assertTrue(failure is PrivilegeServerUnavailableException)
    }

    private class RecordingFileSystem : IPrivilegeFileSystem {
        val binder = TestBinder(localInterface = this)
        val started = CompletableDeferred<String>()
        val cancelledOperationIds = CopyOnWriteArrayList<String>()
        var acceptsOperation: Boolean = true
        var immediateResultCode: Int? = null

        override fun asBinder(): TestBinder = binder

        override fun query(path: String, kind: Int): Boolean = false

        override fun queryLong(path: String, kind: Int): Long = 0L

        override fun stat(path: String, followSymbolicLinks: Boolean): PrivilegeFileResult =
            PrivilegeFileResult.error(0)

        override fun openInput(path: String): PrivilegeFileResult =
            PrivilegeFileResult.error(0)

        override fun openOutput(
            path: String,
            append: Boolean,
            syncOnClose: Boolean,
        ): PrivilegeFileResult = PrivilegeFileResult.error(0)

        override fun createNewFile(path: String): Int = 0

        override fun mkdir(path: String): Boolean = false

        override fun mkdirs(path: String): Boolean = false

        override fun delete(path: String): Boolean = false

        override fun renameTo(sourcePath: String, targetPath: String): Boolean = false

        override fun replaceAtomically(sourcePath: String, targetPath: String): Int = 0

        override fun walk(path: String, maxDepth: Int, sink: ParcelFileDescriptor): Int = 0

        override fun startDeleteRecursively(
            operationId: String,
            path: String,
            receiver: ResultReceiver?,
        ): Boolean {
            started.complete(operationId)
            immediateResultCode?.let { resultCode -> receiver?.send(resultCode, null) }
            return acceptsOperation
        }

        override fun cancelDeleteRecursively(operationId: String) {
            cancelledOperationIds += operationId
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS: Long = 5_000L
    }
}
