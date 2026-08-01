package priv.kit.core.internal.userservice

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.ResultReceiver
import priv.kit.core.userservice.PrivilegeUserServiceException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class PrivilegeUserServiceManagerBinder internal constructor(
    private val registry: PrivilegeUserServiceRegistry,
    private val executor: ThreadPoolExecutor = newExecutor(
        MAX_CONCURRENT_OPERATIONS,
        MAX_QUEUED_OPERATIONS,
        "priv-kit-user-service-operation",
    ),
) : IPrivilegeUserServiceManager.Stub() {
    private val cleanupExecutor = newExecutor(
        MAX_CONCURRENT_CLEANUPS,
        MAX_QUEUED_CLEANUPS,
        "priv-kit-user-service-cleanup",
    )
    private var destroyed = false
    private val operations = ConcurrentHashMap<String, PendingOperation>()
    private val operationSlots = Semaphore(MAX_PENDING_OPERATIONS)
    private val operationAdmissionLock = Any()
    private val cleanupSubmissionLock = Any()

    override fun startUserService(
        operationId: String,
        request: Bundle,
        client: IBinder?,
        receiver: ResultReceiver?,
    ) {
        launchOperation(operationId, receiver, client) { operationClient ->
            val result = registry.start(
                spec = PrivilegeUserServiceContract.specFrom(request),
                client = operationClient,
            )
            OperationResult(
                response = PrivilegeUserServiceContract.successBundle(),
                acceptance = result::accept,
                cleanup = result::rollback,
            )
        }
    }

    override fun bindUserService(
        operationId: String,
        request: Bundle,
        client: IBinder?,
        receiver: ResultReceiver?,
    ) {
        launchOperation(operationId, receiver, client) { operationClient ->
            val result = registry.bind(
                spec = PrivilegeUserServiceContract.specFrom(request),
                client = operationClient,
            )
            OperationResult(
                response = PrivilegeUserServiceContract.bindSuccessBundle(
                    connectionId = result.connectionId,
                    binder = result.binder,
                ),
                acceptance = result::accept,
                cleanup = result::cancel,
            )
        }
    }

    override fun cancelUserServiceOperation(operationId: String) {
        cancelOperation(operationId)
    }

    override fun acknowledgeUserServiceOperation(operationId: String) {
        val operation = operations.remove(operationId) ?: return
        val acceptance = operation.acknowledge()
        operation.unlinkClientDeath()
        try {
            acceptance?.let(::runAction)
        } finally {
            operation.releaseSlot()
        }
    }

    override fun unbindUserService(
        operationId: String,
        connectionId: String,
        client: IBinder?,
        receiver: ResultReceiver?,
    ) {
        launchOperation(operationId, receiver, client) { _ ->
            registry.unbind(connectionId)
            OperationResult(
                response = PrivilegeUserServiceContract.successBundle(),
                acceptance = null,
                cleanup = null,
            )
        }
    }

    override fun stopUserService(
        operationId: String,
        request: Bundle,
        client: IBinder?,
        receiver: ResultReceiver?,
    ) {
        launchOperation(operationId, receiver, client) { _ ->
            registry.stop(PrivilegeUserServiceContract.specFrom(request))
            OperationResult(
                response = PrivilegeUserServiceContract.successBundle(),
                acceptance = null,
                cleanup = null,
            )
        }
    }

    fun destroyOnOwnerDeath() {
        cancelAllOperations()
        registry.destroyOnOwnerDeath()
    }

    fun destroyAll() {
        val destroyExecutors = synchronized(operationAdmissionLock) {
            if (destroyed) {
                false
            } else {
                destroyed = true
                true
            }
        }
        if (destroyExecutors) {
            cancelAllOperations()
            executor.shutdownNow()
            synchronized(cleanupSubmissionLock) {
                cleanupExecutor.shutdown()
            }
        }
        registry.destroyAll()
    }

    private fun launchOperation(
        operationId: String,
        receiver: ResultReceiver?,
        client: IBinder?,
        block: (IBinder) -> OperationResult,
    ) {
        if (receiver == null || operationId.isBlank()) return
        if (client == null) {
            sendError(receiver, "UserService operation client Binder is missing")
            return
        }
        var rejectionMessage: String? = null
        var operation: PendingOperation? = null
        synchronized(operationAdmissionLock) {
            when {
                destroyed -> {
                    rejectionMessage = "UserService manager was destroyed"
                }

                !operationSlots.tryAcquire() -> {
                    rejectionMessage = "Too many pending UserService operations"
                }

                else -> {
                    val candidate = PendingOperation(operationId, receiver, client)
                    if (operations.putIfAbsent(operationId, candidate) == null) {
                        operation = candidate
                    } else {
                        operationSlots.release()
                        rejectionMessage = "Duplicate UserService operation id"
                    }
                }
            }
        }
        if (rejectionMessage != null) {
            sendError(receiver, rejectionMessage)
            return
        }
        val pendingOperation = requireNotNull(operation)
        if (!pendingOperation.linkClientDeath()) {
            operations.remove(operationId, pendingOperation)
            pendingOperation.releaseSlot()
            return
        }

        val future = try {
            executor.submit {
                if (!pendingOperation.beginTask()) return@submit
                try {
                    val result = try {
                        block(client)
                    } catch (throwable: Throwable) {
                        if (pendingOperation.isCancelled) return@submit
                        OperationResult(
                            response = errorBundle(throwable),
                            acceptance = null,
                            cleanup = null,
                        )
                    }

                    if (pendingOperation.isCancelled) {
                        result.cleanup?.let(::runAction)
                        return@submit
                    }
                    if (!pendingOperation.deliver(result.response, result.acceptance, result.cleanup)) {
                        result.cleanup?.let(::runAction)
                    }
                } finally {
                    pendingOperation.finishTask()
                }
            }
        } catch (throwable: Throwable) {
            operations.remove(operationId, pendingOperation)
            pendingOperation.unlinkClientDeath()
            pendingOperation.releaseSlot()
            runCatching {
                receiver.send(RESULT_RESPONSE, errorBundle(throwable))
            }
            return
        }
        pendingOperation.attachFuture(future)
    }

    private fun cancelOperation(operationId: String) {
        val operation = operations.remove(operationId) ?: return
        val cancellation = operation.cancel()
        cancellation.future?.let(::cancelExecutorFuture)
        operation.unlinkClientDeath()
        val cleanup = cancellation.cleanup
        if (cleanup == null) {
            if (cancellation.releaseImmediately) {
                operation.releaseSlot()
            }
        } else {
            executeAction(cleanup, operation::releaseSlot)
        }
    }

    private fun cancelAllOperations() {
        operations.keys.toList().forEach(::cancelOperation)
    }

    private fun executeAction(
        action: () -> Unit,
        completion: () -> Unit,
    ) {
        val submitted = synchronized(cleanupSubmissionLock) {
            if (cleanupExecutor.isShutdown) {
                false
            } else {
                cleanupExecutor.execute {
                    try {
                        runAction(action)
                    } finally {
                        completion()
                    }
                }
                true
            }
        }
        if (!submitted) completion()
    }

    private fun cancelExecutorFuture(future: Future<*>) {
        future.cancel(true)
        executor.purge()
    }

    private fun runAction(action: () -> Unit) {
        runCatching(action)
    }

    private fun errorBundle(throwable: Throwable): Bundle =
        PrivilegeUserServiceContract.errorBundle(
            throwable.message ?: when (throwable) {
                is PrivilegeUserServiceException -> "UserService operation failed"
                else -> throwable.javaClass.name
            },
        )

    private fun sendError(
        receiver: ResultReceiver,
        message: String,
    ) {
        runCatching {
            receiver.send(
                RESULT_RESPONSE,
                PrivilegeUserServiceContract.errorBundle(message),
            )
        }
    }

    private inner class PendingOperation(
        private val id: String,
        private val receiver: ResultReceiver,
        private val client: IBinder,
    ) {
        private val lock = Any()
        private val clientLinked = AtomicBoolean(false)
        private val slotReleased = AtomicBoolean(false)
        private var cancelled = false
        private var acknowledged = false
        private var taskRunning = false
        private var releaseWhenTaskFinishes = false
        private var future: Future<*>? = null
        private var acceptance: (() -> Unit)? = null
        private var cleanup: (() -> Unit)? = null
        private val clientDeathRecipient = IBinder.DeathRecipient {
            cancelOperation(id)
        }

        val isCancelled: Boolean
            get() = synchronized(lock) { cancelled }

        fun linkClientDeath(): Boolean = synchronized(lock) {
            if (cancelled) return@synchronized false
            try {
                clientLinked.set(true)
                client.linkToDeath(clientDeathRecipient, 0)
                !cancelled
            } catch (_: RemoteException) {
                clientLinked.set(false)
                false
            }
        }

        fun unlinkClientDeath() {
            if (!clientLinked.compareAndSet(true, false)) return
            runCatching {
                client.unlinkToDeath(clientDeathRecipient, 0)
            }
        }

        fun attachFuture(value: Future<*>) {
            val cancelNow = synchronized(lock) {
                future = value
                cancelled
            }
            if (cancelNow) {
                cancelExecutorFuture(value)
            }
        }

        fun beginTask(): Boolean = synchronized(lock) {
            if (cancelled) return@synchronized false
            taskRunning = true
            true
        }

        fun finishTask() {
            val release = synchronized(lock) {
                taskRunning = false
                releaseWhenTaskFinishes
            }
            if (release) releaseSlot()
        }

        fun releaseSlot() {
            if (slotReleased.compareAndSet(false, true)) {
                operationSlots.release()
            }
        }

        fun deliver(
            response: Bundle,
            resultAcceptance: (() -> Unit)?,
            resultCleanup: (() -> Unit)?,
        ): Boolean {
            val canDeliver = synchronized(lock) {
                if (cancelled || acknowledged) {
                    false
                } else {
                    acceptance = resultAcceptance
                    cleanup = resultCleanup
                    true
                }
            }
            if (!canDeliver) return false
            return runCatching {
                receiver.send(RESULT_RESPONSE, response)
            }.fold(
                onSuccess = { true },
                onFailure = {
                    cancelOperation(id)
                    true
                },
            )
        }

        fun acknowledge(): (() -> Unit)? =
            synchronized(lock) {
                if (cancelled || acknowledged) return@synchronized null
                acknowledged = true
                cleanup = null
                acceptance.also { acceptance = null }
            }

        fun cancel(): Cancellation {
            synchronized(lock) {
                if (cancelled || acknowledged) return Cancellation(null, null, false)
                cancelled = true
                acceptance = null
                val resultCleanup = cleanup.also { cleanup = null }
                val deferRelease = resultCleanup == null && taskRunning
                releaseWhenTaskFinishes = deferRelease
                return Cancellation(
                    future = future,
                    cleanup = resultCleanup,
                    releaseImmediately = resultCleanup == null && !deferRelease,
                )
            }
        }
    }

    private data class OperationResult(
        val response: Bundle,
        val acceptance: (() -> Unit)?,
        val cleanup: (() -> Unit)?,
    )

    private data class Cancellation(
        val future: Future<*>?,
        val cleanup: (() -> Unit)?,
        val releaseImmediately: Boolean,
    )

    private companion object {
        private const val RESULT_RESPONSE: Int = 0
        private const val MAX_CONCURRENT_OPERATIONS: Int = 4
        private const val MAX_QUEUED_OPERATIONS: Int = 64
        private const val MAX_PENDING_OPERATIONS: Int =
            MAX_CONCURRENT_OPERATIONS + MAX_QUEUED_OPERATIONS
        private const val MAX_CONCURRENT_CLEANUPS: Int = 8
        private const val MAX_QUEUED_CLEANUPS: Int = MAX_PENDING_OPERATIONS

        private val threadCounter = AtomicInteger(0)

        private fun newExecutor(
            maxConcurrent: Int,
            maxQueued: Int,
            threadName: String,
        ): ThreadPoolExecutor =
            ThreadPoolExecutor(
                maxConcurrent,
                maxConcurrent,
                30L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(maxQueued),
                { runnable ->
                    Thread(
                        runnable,
                        "$threadName-${threadCounter.incrementAndGet()}",
                    ).apply {
                        isDaemon = true
                    }
                },
                ThreadPoolExecutor.AbortPolicy(),
            ).apply {
                allowCoreThreadTimeOut(true)
            }
    }
}
