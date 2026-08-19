package priv.kit.core.internal.file

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import priv.kit.core.file.PrivilegeFilePath

internal class PrivilegeFileSystemBinder(
    private val walkAction: suspend (String, Int, ParcelFileDescriptor) -> Unit =
        PrivilegeFileTreeWalker::write,
    walkDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        PrivilegeFileSystemContract.MAX_CONCURRENT_WALKS,
        "priv-kit-file-walk",
    ),
    private val recursiveDeleteAction: suspend (String) -> Boolean =
        PrivilegeFileRecursiveDelete::delete,
    recursiveDeleteDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        PrivilegeFileSystemContract.MAX_CONCURRENT_RECURSIVE_DELETES,
        "priv-kit-file-recursive-delete",
    ),
) : IPrivilegeFileSystem.Stub() {
    private val activeWalks = ConcurrentHashMap<ParcelFileDescriptor, Job>()
    private val activeTransfers = ConcurrentHashMap<ActiveTransfer, Unit>()
    private val activeRecursiveDeletes = ConcurrentHashMap<String, ActiveRecursiveDelete>()
    private val walkSlots = Semaphore(PrivilegeFileSystemContract.MAX_CONCURRENT_WALKS)
    private val transferSlots = Semaphore(PrivilegeFileSystemContract.MAX_CONCURRENT_TRANSFERS)
    private val recursiveDeleteSlots = Semaphore(
        PrivilegeFileSystemContract.MAX_CONCURRENT_RECURSIVE_DELETES,
    )
    private val operationAdmissionLock = Any()
    private val walkJob = SupervisorJob()
    private val walkScope = CoroutineScope(
        walkJob + walkDispatcher + CoroutineName("priv-kit-file-walk"),
    )
    private val recursiveDeleteJob = SupervisorJob()
    private val recursiveDeleteScope = CoroutineScope(
        recursiveDeleteJob + recursiveDeleteDispatcher +
            CoroutineName("priv-kit-file-recursive-delete"),
    )
    private val transferExecutor = ThreadPoolExecutor(
        0,
        PrivilegeFileSystemContract.MAX_CONCURRENT_TRANSFERS,
        TRANSFER_THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        TransferThreadFactory(),
    )

    @Volatile
    private var closed = false

    override fun query(path: String, kind: Int): Boolean {
        validatePath(path)
        val file = File(path)
        return when (kind) {
            PrivilegeFileSystemContract.QUERY_EXISTS -> file.exists()
            PrivilegeFileSystemContract.QUERY_IS_FILE -> file.isFile
            PrivilegeFileSystemContract.QUERY_IS_DIRECTORY -> file.isDirectory
            PrivilegeFileSystemContract.QUERY_CAN_READ -> file.canRead()
            PrivilegeFileSystemContract.QUERY_CAN_WRITE -> file.canWrite()
            PrivilegeFileSystemContract.QUERY_CAN_EXECUTE -> file.canExecute()
            PrivilegeFileSystemContract.QUERY_IS_SYMBOLIC_LINK -> isSymbolicLink(path)
            else -> throw IllegalArgumentException("Unknown file query: $kind")
        }
    }

    override fun queryLong(path: String, kind: Int): Long {
        validatePath(path)
        val file = File(path)
        return when (kind) {
            PrivilegeFileSystemContract.QUERY_LENGTH -> file.length()
            PrivilegeFileSystemContract.QUERY_LAST_MODIFIED -> file.lastModified()
            else -> throw IllegalArgumentException("Unknown long file query: $kind")
        }
    }

    override fun stat(
        path: String,
        followSymbolicLinks: Boolean,
    ): PrivilegeFileResult {
        validatePath(path)
        return try {
            PrivilegeFileResult.metadata(
                PrivilegeFileWire.statToArray(
                    if (followSymbolicLinks) Os.stat(path) else Os.lstat(path),
                ),
            )
        } catch (exception: ErrnoException) {
            PrivilegeFileResult.error(exception.errno)
        }
    }

    override fun openInput(path: String): PrivilegeFileResult {
        validatePath(path)
        if (!reserveTransferSlot()) return PrivilegeFileResult.error(
            if (closed) OsConstants.EPIPE else OsConstants.EBUSY,
        )
        var slotReserved = true
        var targetDescriptor: FileDescriptor? = null
        var target: FileInputStream? = null
        var clientSource: ParcelFileDescriptor? = null
        var serverSink: ParcelFileDescriptor? = null

        return try {
            targetDescriptor = Os.open(
                path,
                OsConstants.O_RDONLY,
                PrivilegeFileSystemContract.CREATE_MODE,
            )
            target = FileInputStream(targetDescriptor)
            targetDescriptor = null
            val pipe = ParcelFileDescriptor.createReliablePipe()
            clientSource = pipe[0]
            serverSink = pipe[1]
            val transfer = ActiveTransfer(
                target = target,
                dataEndpoint = serverSink,
            )
            target = null
            serverSink = null
            activeTransfers[transfer] = Unit
            try {
                transferExecutor.execute {
                    try {
                        transferInput(transfer)
                    } finally {
                        finishTransfer(transfer)
                    }
                }
            } catch (_: RejectedExecutionException) {
                activeTransfers.remove(transfer)
                transfer.cancel()
                return PrivilegeFileResult.error(
                    if (closed) OsConstants.EPIPE else OsConstants.EBUSY,
                )
            }
            slotReserved = false
            PrivilegeFileResult.input(requireNotNull(clientSource).also { clientSource = null })
        } catch (exception: ErrnoException) {
            PrivilegeFileResult.error(exception.errno)
        } catch (_: IOException) {
            PrivilegeFileResult.error(OsConstants.EIO)
        } finally {
            targetDescriptor?.let { descriptor -> runCatching { Os.close(descriptor) } }
            runCatching { target?.close() }
            runCatching { clientSource?.close() }
            runCatching { serverSink?.close() }
            if (slotReserved) transferSlots.release()
        }
    }

    override fun openOutput(
        path: String,
        append: Boolean,
        syncOnClose: Boolean,
    ): PrivilegeFileResult {
        validatePath(path)
        if (!reserveTransferSlot()) return PrivilegeFileResult.error(
            if (closed) OsConstants.EPIPE else OsConstants.EBUSY,
        )
        var slotReserved = true
        var targetDescriptor: FileDescriptor? = null
        var target: FileOutputStream? = null
        var serverSource: ParcelFileDescriptor? = null
        var clientSink: ParcelFileDescriptor? = null
        var clientCompletion: ParcelFileDescriptor? = null
        var serverCompletion: ParcelFileDescriptor? = null

        return try {
            val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or if (append) {
                OsConstants.O_APPEND
            } else {
                OsConstants.O_TRUNC
            }
            targetDescriptor = Os.open(
                path,
                flags,
                PrivilegeFileSystemContract.CREATE_MODE,
            )
            target = FileOutputStream(targetDescriptor)
            targetDescriptor = null
            val dataPipe = ParcelFileDescriptor.createReliablePipe()
            serverSource = dataPipe[0]
            clientSink = dataPipe[1]
            val completionPipe = ParcelFileDescriptor.createReliablePipe()
            clientCompletion = completionPipe[0]
            serverCompletion = completionPipe[1]
            val transfer = ActiveTransfer(
                target = target,
                dataEndpoint = serverSource,
                completionEndpoint = serverCompletion,
                syncOnClose = syncOnClose,
            )
            target = null
            serverSource = null
            serverCompletion = null
            activeTransfers[transfer] = Unit
            try {
                transferExecutor.execute {
                    try {
                        transferOutput(transfer)
                    } finally {
                        finishTransfer(transfer)
                    }
                }
            } catch (_: RejectedExecutionException) {
                activeTransfers.remove(transfer)
                transfer.cancel()
                return PrivilegeFileResult.error(
                    if (closed) OsConstants.EPIPE else OsConstants.EBUSY,
                )
            }
            slotReserved = false
            PrivilegeFileResult.output(
                value = requireNotNull(clientSink).also { clientSink = null },
                completion = requireNotNull(clientCompletion).also { clientCompletion = null },
            )
        } catch (exception: ErrnoException) {
            PrivilegeFileResult.error(exception.errno)
        } catch (_: IOException) {
            PrivilegeFileResult.error(OsConstants.EIO)
        } finally {
            targetDescriptor?.let { descriptor -> runCatching { Os.close(descriptor) } }
            runCatching { target?.close() }
            runCatching { serverSource?.close() }
            runCatching { clientSink?.close() }
            runCatching { clientCompletion?.close() }
            runCatching { serverCompletion?.close() }
            if (slotReserved) transferSlots.release()
        }
    }

    override fun createNewFile(path: String): Int {
        validatePath(path)
        return try {
            val descriptor = Os.open(
                path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL,
                PrivilegeFileSystemContract.CREATE_MODE,
            )
            Os.close(descriptor)
            1
        } catch (exception: ErrnoException) {
            if (exception.errno == OsConstants.EEXIST) {
                0
            } else {
                -exception.errno
            }
        }
    }

    override fun mkdir(path: String): Boolean {
        validatePath(path)
        return File(path).mkdir()
    }

    override fun mkdirs(path: String): Boolean {
        validatePath(path)
        return File(path).mkdirs()
    }

    override fun delete(path: String): Boolean {
        validatePath(path)
        return File(path).delete()
    }

    override fun startDeleteRecursively(
        operationId: String,
        path: String,
        receiver: ResultReceiver?,
    ): Boolean {
        if (operationId.isBlank() || receiver == null) return false

        val operation = synchronized(operationAdmissionLock) {
            if (closed || !recursiveDeleteSlots.tryAcquire()) return false
            val candidate = ActiveRecursiveDelete(receiver)
            if (activeRecursiveDeletes.putIfAbsent(operationId, candidate) != null) {
                recursiveDeleteSlots.release()
                return false
            }
            candidate
        }

        val job = recursiveDeleteScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.complete(recursiveDeleteAction(path))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                operation.complete(false)
            }
        }
        operation.attach(job)
        job.invokeOnCompletion {
            if (activeRecursiveDeletes.remove(operationId, operation)) {
                recursiveDeleteSlots.release()
            }
        }
        job.start()
        return true
    }

    override fun cancelDeleteRecursively(operationId: String) {
        activeRecursiveDeletes[operationId]?.cancel()
    }

    override fun renameTo(sourcePath: String, targetPath: String): Boolean {
        validatePath(sourcePath)
        validatePath(targetPath)
        return File(sourcePath).renameTo(File(targetPath))
    }

    override fun replaceAtomically(sourcePath: String, targetPath: String): Int {
        validatePath(sourcePath)
        validatePath(targetPath)
        return try {
            Os.rename(sourcePath, targetPath)
            0
        } catch (exception: ErrnoException) {
            exception.errno
        }
    }

    override fun walk(
        path: String,
        maxDepth: Int,
        sink: ParcelFileDescriptor,
    ): Int {
        validatePath(path)
        require(maxDepth >= 1) { "Maximum walk depth must be positive: $maxDepth" }

        val job = walkScope.launch(start = CoroutineStart.LAZY) {
            walkAction(path, maxDepth, sink)
        }
        val accepted = synchronized(operationAdmissionLock) {
            if (closed || !walkSlots.tryAcquire()) {
                false
            } else if (activeWalks.putIfAbsent(sink, job) != null) {
                walkSlots.release()
                false
            } else {
                true
            }
        }
        if (!accepted) {
            job.cancel()
            sink.close()
            return if (closed) OsConstants.EPIPE else OsConstants.EBUSY
        }

        job.invokeOnCompletion {
            if (activeWalks.remove(sink, job)) {
                runCatching(sink::close)
                walkSlots.release()
            }
        }
        job.start()
        return 0
    }

    fun cancelActiveWalks() {
        activeWalks.forEach { (sink, job) ->
            runCatching(sink::close)
            job.cancel()
        }
    }

    fun cancelActiveOperations() {
        cancelActiveWalks()
        activeTransfers.keys.forEach(ActiveTransfer::cancel)
        activeRecursiveDeletes.values.forEach(ActiveRecursiveDelete::cancel)
    }

    fun shutdown() {
        synchronized(operationAdmissionLock) {
            closed = true
        }
        cancelActiveOperations()
        walkJob.cancel()
        recursiveDeleteJob.cancel()
        transferExecutor.shutdownNow()
    }

    private fun reserveTransferSlot(): Boolean {
        if (closed || !transferSlots.tryAcquire()) return false
        if (closed) {
            transferSlots.release()
            return false
        }
        return true
    }

    private fun transferInput(transfer: ActiveTransfer) {
        val source = transfer.target as FileInputStream
        val sink = transfer.dataEndpoint
        val output = ParcelFileDescriptor.AutoCloseOutputStream(sink)
        var failure: Throwable? = null
        try {
            source.copyTo(output)
            output.flush()
            if (transfer.cancelled) throw IOException("File transfer was cancelled")
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            runCatching(source::close)
            if (failure == null) {
                runCatching(output::close)
            } else {
                runCatching { sink.closeWithError(failure.toTransferMessage()) }
                runCatching(output::close)
            }
        }
    }

    private fun transferOutput(transfer: ActiveTransfer) {
        val target = transfer.target as FileOutputStream
        val source = transfer.dataEndpoint
        var failure: Throwable? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                input.copyTo(target)
            }
            if (transfer.cancelled) throw IOException("File transfer was cancelled")
            target.flush()
            if (transfer.syncOnClose) Os.fsync(target.fd)
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            try {
                target.close()
            } catch (throwable: Throwable) {
                if (failure == null) {
                    failure = throwable
                } else {
                    failure.addSuppressed(throwable)
                }
            }
            writeTransferCompletion(transfer.completionEndpoint, failure)
        }
    }

    private fun writeTransferCompletion(
        sink: ParcelFileDescriptor?,
        failure: Throwable?,
    ) {
        if (sink == null) return
        val output = DataOutputStream(
            BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(sink)),
        )
        try {
            output.writeInt(failure?.errnoOrEio() ?: 0)
            output.writeUTF(failure?.toTransferMessage().orEmpty())
            output.flush()
        } catch (_: IOException) {
        } finally {
            runCatching(output::close)
        }
    }

    private fun finishTransfer(transfer: ActiveTransfer) {
        activeTransfers.remove(transfer)
        transfer.close()
        transferSlots.release()
    }

    private fun isSymbolicLink(path: String): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    private fun validatePath(path: String) {
        PrivilegeFilePath.validateAbsolute(path)
    }

    private fun Throwable.errnoOrEio(): Int {
        var current: Throwable? = this
        while (current != null) {
            if (current is ErrnoException) return current.errno
            current = current.cause
        }
        return OsConstants.EIO
    }

    private fun Throwable?.toTransferMessage(): String {
        val throwable = this
        return throwable?.message?.take(MAX_TRANSFER_ERROR_CHARS).orEmpty().ifBlank {
            throwable?.javaClass?.simpleName ?: "File transfer failed"
        }
    }

    private class TransferThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread = Thread(
            runnable,
            "priv-kit-file-transfer-${sequence.incrementAndGet()}",
        ).apply {
            isDaemon = true
        }
    }

    private class ActiveTransfer(
        val target: Closeable,
        val dataEndpoint: ParcelFileDescriptor,
        val completionEndpoint: ParcelFileDescriptor? = null,
        val syncOnClose: Boolean = false,
    ) : Closeable {
        @Volatile
        var cancelled: Boolean = false
            private set

        fun cancel() {
            cancelled = true
            runCatching { dataEndpoint.closeWithError("File transfer was cancelled") }
            runCatching { completionEndpoint?.closeWithError("File transfer was cancelled") }
            runCatching(target::close)
        }

        override fun close() {
            runCatching(dataEndpoint::close)
            runCatching { completionEndpoint?.close() }
            runCatching(target::close)
        }
    }

    private class ActiveRecursiveDelete(
        private val receiver: ResultReceiver,
    ) {
        private val state = AtomicInteger(STATE_PENDING)
        private val job = AtomicReference<Job?>()

        fun attach(value: Job) {
            check(job.compareAndSet(null, value)) { "Recursive delete Job was already attached" }
            if (state.get() == STATE_CANCELLED) value.cancel()
        }

        fun complete(succeeded: Boolean) {
            if (!state.compareAndSet(STATE_PENDING, STATE_COMPLETED)) return
            runCatching {
                receiver.send(
                    if (succeeded) {
                        PrivilegeFileSystemContract.RECURSIVE_DELETE_SUCCEEDED
                    } else {
                        PrivilegeFileSystemContract.RECURSIVE_DELETE_FAILED
                    },
                    null,
                )
            }
        }

        fun cancel() {
            if (state.compareAndSet(STATE_PENDING, STATE_CANCELLED)) {
                job.get()?.cancel()
            }
        }

        private companion object {
            const val STATE_PENDING: Int = 0
            const val STATE_COMPLETED: Int = 1
            const val STATE_CANCELLED: Int = 2
        }
    }

    private companion object {
        const val TRANSFER_THREAD_KEEP_ALIVE_SECONDS: Long = 30L
        const val MAX_TRANSFER_ERROR_CHARS: Int = 2_048
    }
}
