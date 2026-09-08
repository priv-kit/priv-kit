package priv.kit.core.internal.command

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegeCommandExecutorBinder internal constructor(
    private val processStarter: (PrivilegeCommandRequest) -> Process = ::startProcess,
) : IPrivilegeCommandExecutor.Stub() {
    private val operationExecutor: ExecutorService = newExecutor(
        MAX_CONCURRENT_COMMANDS,
        "priv-kit-command",
    )
    private val pumpExecutor: ExecutorService = newExecutor(
        MAX_CONCURRENT_COMMANDS * 2,
        "priv-kit-command-output",
    )
    private val timeoutExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            newThread(runnable, "priv-kit-command-timeout")
        }
    private val operations = ConcurrentHashMap<String, PendingCommand>()
    private val operationSlots = Semaphore(MAX_CONCURRENT_COMMANDS)
    private val operationAdmissionLock = Any()
    private val destroyed = AtomicBoolean(false)

    override fun startCommand(
        operationId: String?,
        request: Bundle?,
        client: IBinder?,
        stdoutSink: ParcelFileDescriptor?,
        stderrSink: ParcelFileDescriptor?,
        receiver: ResultReceiver?,
    ) {
        val ownedStdout = stdoutSink
        val ownedStderr = stderrSink
        if (
            receiver == null || client == null || operationId.isNullOrBlank() || request == null
        ) {
            ownedStdout.closeQuietly()
            ownedStderr.closeQuietly()
            receiver?.sendFailure("Command request is missing required Binder arguments")
            return
        }
        if (ownedStdout == null || ownedStderr == null) {
            ownedStdout.closeQuietly()
            ownedStderr.closeQuietly()
            receiver.sendFailure("Command request is missing output descriptors")
            return
        }
        val commandRequest = try {
            PrivilegeCommandContract.requestFrom(request)
        } catch (throwable: Throwable) {
            ownedStdout.closeQuietly()
            ownedStderr.closeQuietly()
            receiver.sendFailure(throwable.message ?: "Invalid command request")
            return
        }
        val operation = PendingCommand(
            id = operationId,
            client = client,
            receiver = receiver,
            stdoutSink = ownedStdout,
            stderrSink = ownedStderr,
        )
        val schedulingFailure = synchronized(operationAdmissionLock) {
            when {
                destroyed.get() -> "Command executor was destroyed"
                !operationSlots.tryAcquire() -> "Too many active commands"
                operations.putIfAbsent(operationId, operation) != null -> {
                    operationSlots.release()
                    "Duplicate command operation id"
                }

                else -> try {
                    operationExecutor.execute {
                        runOperation(operation, commandRequest)
                    }
                    null
                } catch (throwable: Throwable) {
                    operations.remove(operationId, operation)
                    operationSlots.release()
                    throwable.message ?: "Unable to schedule command"
                }
            }
        }
        if (schedulingFailure != null) {
            operation.closeDescriptors()
            receiver.sendFailure(schedulingFailure)
        }
    }

    override fun cancelCommand(operationId: String?) {
        if (operationId != null) {
            operations[operationId]?.terminate(Termination.CANCELLED)
        }
    }

    fun cancelActiveOperations() {
        operations.values.toList().forEach { operation ->
            operation.terminate(Termination.CANCELLED)
        }
    }

    fun shutdown() {
        val activeOperations = synchronized(operationAdmissionLock) {
            if (!destroyed.compareAndSet(false, true)) return
            operationExecutor.shutdown()
            operations.values.toList()
        }
        activeOperations.forEach { operation ->
            operation.terminate(Termination.CANCELLED)
        }
        pumpExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()
    }

    private fun runOperation(
        operation: PendingCommand,
        request: PrivilegeCommandRequest,
    ) {
        var exitCode: Int? = null
        var failure: Throwable? = null
        try {
            if (!operation.linkClientDeath()) {
                operation.terminate(Termination.CANCELLED)
                return
            }
            if (operation.termination != Termination.NONE) return
            val process = processStarter(request)
            if (!operation.attachProcess(process)) {
                destroyProcess(process)
                return
            }
            runCatching { process.outputStream.close() }
            val stdoutPump = pumpExecutor.submit {
                pump(process.inputStream, operation.stdoutSink)
            }
            val stderrPump = pumpExecutor.submit {
                pump(process.errorStream, operation.stderrSink)
            }
            operation.attachPumps(stdoutPump, stderrPump)
            operation.scheduleTimeout(request.timeoutMillis)
            if (!operation.sendStarted()) {
                operation.terminate(Termination.CANCELLED)
                return
            }

            exitCode = process.waitFor()
            stdoutPump.get()
            stderrPump.get()
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            val terminalAction: (() -> Unit)? = when (operation.termination) {
                Termination.CANCELLED -> null
                Termination.TIMED_OUT -> operation::sendTimedOut
                Termination.NONE -> {
                    val code = exitCode
                    if (failure == null && code != null) {
                        { operation.sendExited(code) }
                    } else {
                        val message = failure?.message ?: "Command ended without an exit code"
                        { operation.sendFailure(message) }
                    }
                }
            }
            operation.finish()
            terminalAction?.invoke()
        }
    }

    private inner class PendingCommand(
        private val id: String,
        private val client: IBinder,
        private val receiver: ResultReceiver,
        val stdoutSink: ParcelFileDescriptor,
        val stderrSink: ParcelFileDescriptor,
    ) {
        private val clientLinked = AtomicBoolean(false)
        private val terminationReference = AtomicReference(Termination.NONE)
        private val processReference = AtomicReference<Process?>()
        private val timeoutReference = AtomicReference<Future<*>?>()
        private val stdoutPumpReference = AtomicReference<Future<*>?>()
        private val stderrPumpReference = AtomicReference<Future<*>?>()
        private val clientDeathRecipient = IBinder.DeathRecipient {
            terminate(Termination.CANCELLED)
        }

        val termination: Termination
            get() = terminationReference.get()

        fun linkClientDeath(): Boolean {
            clientLinked.set(true)
            return try {
                client.linkToDeath(clientDeathRecipient, 0)
                client.pingBinder()
            } catch (_: Throwable) {
                clientLinked.set(false)
                false
            }
        }

        fun attachProcess(process: Process): Boolean {
            processReference.set(process)
            if (termination == Termination.NONE) return true
            processReference.compareAndSet(process, null)
            return false
        }

        fun attachPumps(stdoutPump: Future<*>, stderrPump: Future<*>) {
            stdoutPumpReference.set(stdoutPump)
            stderrPumpReference.set(stderrPump)
            if (termination != Termination.NONE) {
                closeDescriptors()
                stdoutPump.cancel(true)
                stderrPump.cancel(true)
            }
        }

        fun scheduleTimeout(timeoutMillis: Long) {
            if (timeoutMillis == 0L) return
            val future = timeoutExecutor.schedule(
                { terminate(Termination.TIMED_OUT) },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            timeoutReference.set(future)
            if (termination != Termination.NONE) future.cancel(false)
        }

        fun sendStarted(): Boolean = send(PrivilegeCommandContract.RESULT_STARTED, Bundle.EMPTY)

        fun sendExited(exitCode: Int) {
            send(
                PrivilegeCommandContract.RESULT_EXITED,
                PrivilegeCommandContract.exitBundle(exitCode),
            )
        }

        fun sendTimedOut() {
            send(PrivilegeCommandContract.RESULT_TIMED_OUT, Bundle.EMPTY)
        }

        fun sendFailure(message: String) {
            send(
                PrivilegeCommandContract.RESULT_FAILED,
                PrivilegeCommandContract.errorBundle(message),
            )
        }

        fun terminate(reason: Termination) {
            if (!terminationReference.compareAndSet(Termination.NONE, reason)) return
            closeDescriptors()
            stdoutPumpReference.get()?.cancel(true)
            stderrPumpReference.get()?.cancel(true)
            processReference.get()?.let(::destroyProcess)
        }

        fun closeDescriptors() {
            stdoutSink.closeQuietly()
            stderrSink.closeQuietly()
        }

        fun finish() {
            timeoutReference.getAndSet(null)?.cancel(false)
            closeDescriptors()
            processReference.getAndSet(null)?.let { process ->
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                if (process.isAlive) destroyProcess(process)
            }
            if (clientLinked.compareAndSet(true, false)) {
                runCatching { client.unlinkToDeath(clientDeathRecipient, 0) }
            }
            operations.remove(id, this)
            operationSlots.release()
        }

        private fun send(resultCode: Int, data: Bundle): Boolean =
            runCatching {
                receiver.send(resultCode, data)
            }.isSuccess
    }

    private enum class Termination {
        NONE,
        CANCELLED,
        TIMED_OUT,
    }

    private companion object {
        private const val MAX_CONCURRENT_COMMANDS: Int = 4
        private val threadCounter = AtomicInteger(0)

        private fun startProcess(request: PrivilegeCommandRequest): Process {
            val command = request.command
            return ProcessBuilder(command.arguments)
                .apply {
                    environment().putAll(command.environment)
                    command.workingDirectory?.let { directory(File(it)) }
                    redirectErrorStream(false)
                }
                .start()
        }

        private fun newExecutor(threadCount: Int, name: String): ExecutorService =
            ThreadPoolExecutor(
                threadCount,
                threadCount,
                EXECUTOR_THREAD_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                { runnable -> newThread(runnable, name) },
            ).apply {
                allowCoreThreadTimeOut(true)
            }

        private fun newThread(runnable: Runnable, name: String): Thread =
            Thread(
                runnable,
                "$name-${threadCounter.incrementAndGet()}",
            ).apply {
                isDaemon = true
            }

        private fun pump(input: InputStream, sink: ParcelFileDescriptor) {
            input.use { source ->
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { output ->
                    source.copyTo(output)
                }
            }
        }

        private fun destroyProcess(process: Process) {
            runCatching { process.destroy() }
            if (runCatching { process.isAlive }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
            }
        }

        private const val EXECUTOR_THREAD_KEEP_ALIVE_SECONDS: Long = 30L
    }
}

private fun ParcelFileDescriptor?.closeQuietly() {
    if (this != null) runCatching(::close)
}

private fun ResultReceiver.sendFailure(message: String) {
    runCatching {
        send(
            PrivilegeCommandContract.RESULT_FAILED,
            PrivilegeCommandContract.errorBundle(message),
        )
    }
}
