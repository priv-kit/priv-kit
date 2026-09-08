package priv.kit.core.internal.command

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.command.PrivilegeCommandEvent
import priv.kit.core.command.PrivilegeCommandException
import priv.kit.core.command.PrivilegeCommandProcess
import priv.kit.core.command.PrivilegeCommandResult
import priv.kit.core.command.PrivilegeCommandTimeoutException

internal class PrivilegeCommandClient(
    private val executorProvider: () -> IPrivilegeCommandExecutor,
) {
    private val ownerBinder = Binder()

    suspend fun start(
        command: PrivilegeCommand,
        timeoutMillis: Long?,
    ): PrivilegeCommandProcess {
        require(timeoutMillis == null || timeoutMillis > 0L) {
            "timeoutMillis must be positive or null"
        }
        val executor = executorProvider()
        val stdoutPipe = ParcelFileDescriptor.createReliablePipe()
        val stderrPipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (throwable: Throwable) {
            stdoutPipe.closeAll()
            throw throwable
        }
        val operationId = UUID.randomUUID().toString()
        val state = PrivilegeCommandProcessState(
            executor = executor,
            operationId = operationId,
            timeoutMillis = timeoutMillis,
            stdoutSource = stdoutPipe[0],
            stderrSource = stderrPipe[0],
        )
        val stdoutSink = stdoutPipe[1]
        val stderrSink = stderrPipe[1]
        val localExecutor = executorBinderIsLocal(executor)

        try {
            state.linkExecutorDeath()
            executor.startCommand(
                operationId,
                PrivilegeCommandContract.requestBundle(command, timeoutMillis),
                ownerBinder,
                stdoutSink,
                stderrSink,
                state.receiver,
            )
        } catch (throwable: Throwable) {
            state.failBeforeStart(throwable)
        } finally {
            if (!localExecutor) {
                runCatching(stdoutSink::close)
                runCatching(stderrSink::close)
            }
        }

        try {
            state.awaitStarted()
            return state
        } catch (throwable: Throwable) {
            state.cancel()
            throw throwable
        }
    }
}

private fun executorBinderIsLocal(executor: IPrivilegeCommandExecutor): Boolean =
    executor.asBinder().queryLocalInterface(IPrivilegeCommandExecutor.DESCRIPTOR) != null

private class PrivilegeCommandProcessState(
    private val executor: IPrivilegeCommandExecutor,
    private val operationId: String,
    private val timeoutMillis: Long?,
    private val stdoutSource: ParcelFileDescriptor,
    private val stderrSource: ParcelFileDescriptor,
) : PrivilegeCommandProcess() {
    private val started = CompletableDeferred<Unit>()
    private val exited = CompletableDeferred<Int>()
    private val consumption = AtomicReference(Consumption.NONE)
    private val cancelled = AtomicBoolean(false)
    private val descriptorsClosed = AtomicBoolean(false)
    private val executorLinked = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private val executorBinder = executor.asBinder()
    private val executorDeathRecipient = IBinder.DeathRecipient {
        fail(
            PrivilegeCommandException(
                "Command executor died",
                DeadObjectException("Command executor died"),
            ),
        )
        closeDescriptors()
    }

    val receiver: ResultReceiver = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                PrivilegeCommandContract.RESULT_STARTED -> started.complete(Unit)

                PrivilegeCommandContract.RESULT_EXITED -> {
                    val data = resultData
                    if (data == null || !data.containsKey(PrivilegeCommandContract.KEY_EXIT_CODE)) {
                        fail(PrivilegeCommandException("Command exit response is incomplete"))
                        unlinkExecutorDeath()
                    } else {
                        if (!started.isCompleted) started.complete(Unit)
                        exited.complete(data.getInt(PrivilegeCommandContract.KEY_EXIT_CODE))
                        unlinkExecutorDeath()
                    }
                }

                PrivilegeCommandContract.RESULT_TIMED_OUT -> {
                    val exception = PrivilegeCommandTimeoutException(
                        timeoutMillis = timeoutMillis ?: 0L,
                    )
                    fail(exception)
                    unlinkExecutorDeath()
                }

                PrivilegeCommandContract.RESULT_FAILED -> {
                    val message = resultData
                        ?.getString(PrivilegeCommandContract.KEY_ERROR_MESSAGE)
                        ?.takeIf(String::isNotBlank)
                        ?: "Command execution failed"
                    fail(PrivilegeCommandException(message))
                    unlinkExecutorDeath()
                }

                else -> {
                    fail(PrivilegeCommandException("Unknown command result code: $resultCode"))
                    unlinkExecutorDeath()
                }
            }
        }
    }

    fun linkExecutorDeath() {
        executorLinked.set(true)
        try {
            executorBinder.linkToDeath(executorDeathRecipient, 0)
        } catch (throwable: Throwable) {
            executorLinked.set(false)
            throw throwable
        }
        if (!executorBinder.pingBinder()) {
            unlinkExecutorDeath()
            throw DeadObjectException("Command executor died before command start")
        }
    }

    suspend fun awaitStarted() {
        started.await()
    }

    fun failBeforeStart(throwable: Throwable) {
        fail(throwable)
        closeDescriptors()
    }

    override fun stream(): Flow<PrivilegeCommandEvent> = channelFlow {
        claim(Consumption.STREAM)
        var completedNormally = false
        val stdoutReader = launch(Dispatchers.IO) {
            readChunks(stdoutSource) { bytes ->
                send(PrivilegeCommandEvent.Stdout(bytes))
            }
        }
        val stderrReader = launch(Dispatchers.IO) {
            readChunks(stderrSource) { bytes ->
                send(PrivilegeCommandEvent.Stderr(bytes))
            }
        }
        try {
            stdoutReader.join()
            stderrReader.join()
            send(PrivilegeCommandEvent.Exited(exited.await()))
            completedNormally = true
        } finally {
            if (completedNormally) {
                releaseLocalResources()
            } else {
                cancel()
            }
        }
    }

    override suspend fun awaitResult(maxBytesPerStream: Int): PrivilegeCommandResult {
        require(maxBytesPerStream > 0) { "maxBytesPerStream must be positive" }
        claim(Consumption.RESULT)
        return coroutineScope {
            var completedNormally = false
            val stdoutCapture = async(Dispatchers.IO) {
                readCapture(stdoutSource, maxBytesPerStream)
            }
            val stderrCapture = async(Dispatchers.IO) {
                readCapture(stderrSource, maxBytesPerStream)
            }
            try {
                val stdout = stdoutCapture.await()
                val stderr = stderrCapture.await()
                PrivilegeCommandResult(
                    exitCode = exited.await(),
                    stdout = stdout.bytes,
                    stderr = stderr.bytes,
                    stdoutTruncated = stdout.truncated,
                    stderrTruncated = stderr.truncated,
                ).also {
                    completedNormally = true
                }
            } finally {
                if (completedNormally) {
                    releaseLocalResources()
                } else {
                    cancel()
                }
            }
        }
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val shouldCancelRemote = !exited.isCompleted
        val exception = CancellationException("Command was cancelled")
        fail(exception)
        closeDescriptors()
        unlinkExecutorDeath()
        if (shouldCancelRemote) {
            runCatching {
                executor.cancelCommand(operationId)
            }
        }
    }

    private fun claim(requested: Consumption) {
        if (!consumption.compareAndSet(Consumption.NONE, requested)) {
            throw IllegalStateException(
                "Command output is already being consumed as ${consumption.get().description}",
            )
        }
        if (cancelled.get()) throw CancellationException("Command was cancelled")
    }

    private fun fail(throwable: Throwable) {
        terminalFailure.compareAndSet(null, throwable)
        val failure = checkNotNull(terminalFailure.get())
        started.completeExceptionally(failure)
        exited.completeExceptionally(failure)
    }

    private fun releaseLocalResources() {
        closeDescriptors()
        unlinkExecutorDeath()
    }

    private fun closeDescriptors() {
        if (!descriptorsClosed.compareAndSet(false, true)) return
        runCatching(stdoutSource::close)
        runCatching(stderrSource::close)
    }

    private fun unlinkExecutorDeath() {
        if (!executorLinked.compareAndSet(true, false)) return
        runCatching {
            executorBinder.unlinkToDeath(executorDeathRecipient, 0)
        }
    }

    private suspend fun readChunks(
        descriptor: ParcelFileDescriptor,
        emit: suspend (ByteArray) -> Unit,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) return
                    if (count > 0) emit(buffer.copyOf(count))
                }
            }
        } catch (throwable: Throwable) {
            throw terminalFailure.get() ?: throwable
        }
    }

    private fun readCapture(
        descriptor: ParcelFileDescriptor,
        maxBytes: Int,
    ): Capture {
        val output = ByteArrayOutputStream(minOf(BUFFER_SIZE, maxBytes))
        var truncated = false
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val remaining = maxBytes - output.size()
                    if (remaining > 0) {
                        output.write(buffer, 0, minOf(remaining, count))
                    }
                    if (count > remaining) truncated = true
                }
            }
        } catch (throwable: Throwable) {
            throw terminalFailure.get() ?: throwable
        }
        return Capture(output.toByteArray(), truncated)
    }

    private enum class Consumption(val description: String) {
        NONE("nothing"),
        STREAM("a stream"),
        RESULT("a result"),
    }

    private data class Capture(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    private companion object {
        private const val BUFFER_SIZE: Int = 8 * 1024
    }
}

private fun Array<ParcelFileDescriptor>.closeAll() {
    forEach { descriptor -> runCatching(descriptor::close) }
}
