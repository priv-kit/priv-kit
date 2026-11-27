package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.DeadObjectException
import android.os.RemoteException
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.userservice.PrivilegeUserServiceId
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

internal class PrivilegeUserServiceRegistry internal constructor(
    private val host: PrivilegeUserServiceHost,
    private val embeddedContextRuntimeProvider: () -> PrivilegeUserServiceLoader.ContextRuntime,
    private val dedicatedStartTimeoutMillis: Long = DEFAULT_DEDICATED_START_TIMEOUT_MILLIS,
) {
    private val stateLock = Any()
    private val serviceLocks = Array(SERVICE_LOCK_STRIPE_COUNT) { ReentrantLock() }
    private val records = mutableMapOf<PrivilegeUserServiceId, Record>()
    private val connections = mutableMapOf<String, Connection>()

    internal fun bind(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
        daemon: Boolean = false,
        onDisconnected: () -> Unit = {},
    ): BindResult {
        val id = spec.id()
        return withServiceLockInterruptibly(id) {
            val ensured = ensureRecord(spec)
            bindRecord(ensured, client, daemon, onDisconnected)
        }
    }

    internal fun peek(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
        onDisconnected: () -> Unit = {},
    ): BindResult? {
        val id = spec.id()
        return withServiceLockInterruptibly(id) {
            val record = synchronized(stateLock) { records[id] }
                ?: return@withServiceLockInterruptibly null
            if (record.spec.process != spec.process || !record.isRunning || !record.isAlive) {
                return@withServiceLockInterruptibly null
            }
            bindRecord(EnsuredRecord(record, created = false), client, daemon = null, onDisconnected)
        }
    }

    private fun bindRecord(
        ensured: EnsuredRecord,
        client: IBinder,
        daemon: Boolean?,
        onDisconnected: () -> Unit,
    ): BindResult {
        val id = ensured.record.spec.id()
        val connectionId = UUID.randomUUID().toString()
        var linked = false
        val daemonRevision = daemon?.let { ensured.record.nextDaemonRevision() }
        try {
            ensureNotInterrupted()
            val binder = ensured.record.bind(connectionId)
            ensureNotInterrupted()
            linkConnection(
                record = ensured.record,
                serviceId = id,
                connectionId = connectionId,
                client = client,
                onDisconnected = onDisconnected,
            )
            linked = true
            ensureNotInterrupted()
            return BindResult(
                connectionId = connectionId,
                binder = binder,
                acceptAction = {
                    if (daemon != null && daemonRevision != null) {
                        acceptBind(connectionId, id, daemon, daemonRevision)
                    }
                },
                cancelAction = { cancelBind(connectionId, id) },
            )
        } catch (throwable: Throwable) {
            if (linked) {
                unbindLockedByService(connectionId)
            } else {
                ensured.record.unbind(connectionId)
            }
            destroyCreatedUnusedRecord(id, ensured)
            if (throwable is DeadObjectException) {
                throw PrivilegeUnavailableException(throwable.message ?: "UserService process is unavailable", throwable)
            }
            throw throwable
        }
    }

    internal fun unbind(connectionId: String) {
        val connection = synchronized(stateLock) {
            connections[connectionId]
        } ?: return
        withServiceLockInterruptibly(connection.serviceId) {
            unbindLockedByService(connectionId)
        }
    }

    internal fun stop(spec: PrivilegeUserServiceSpec) {
        val id = spec.id()
        withServiceLockInterruptibly(id) {
            val record = synchronized(stateLock) {
                records[id]
            } ?: return@withServiceLockInterruptibly
            destroyRecord(id, record)
        }
    }

    internal fun destroyOnOwnerDeath() {
        val ids = synchronized(stateLock) {
            records.keys.toList()
        }
        ids.forEach { id ->
            withServiceLock(id) {
                val record = synchronized(stateLock) {
                    records[id]
                } ?: return@withServiceLock
                if (!record.daemon) {
                    destroyRecord(id, record)
                }
            }
        }
    }

    internal fun destroyAll() {
        val ids = synchronized(stateLock) {
            records.keys.toList()
        }
        ids.forEach { id ->
            withServiceLock(id) {
                synchronized(stateLock) {
                    records[id]
                }?.let { record ->
                    destroyRecord(id, record)
                }
            }
        }
    }

    private fun ensureRecord(spec: PrivilegeUserServiceSpec): EnsuredRecord {
        val id = spec.id()
        val current = synchronized(stateLock) {
            records[id]
        }
        if (
            current != null &&
            current.spec.process == spec.process &&
            current.isRunning
        ) {
            return EnsuredRecord(current, created = false)
        }

        if (current != null) {
            destroyRecord(id, current)
        }

        ensureNotInterrupted()
        val record = if (spec.process.isEmbedded) createEmbeddedRecord(spec) else createDedicatedRecord(spec)
        synchronized(stateLock) {
            records[id] = record
        }
        try {
            ensureNotInterrupted()
            record.onRegistered()
            ensureNotInterrupted()
        } catch (throwable: Throwable) {
            synchronized(stateLock) {
                records.remove(id, record)
            }
            record.destroy()
            if (throwable is DeadObjectException) throw PrivilegeUnavailableException(cause = throwable)
            throw throwable
        }
        return EnsuredRecord(record, created = true)
    }

    private fun createDedicatedRecord(spec: PrivilegeUserServiceSpec): Record {
        val token = UUID.randomUUID().toString()
        val handle = host.startDedicatedProcess(spec, token)
        return try {
            val process = host.awaitDedicatedProcess(token, handle, dedicatedStartTimeoutMillis)
            ensureNotInterrupted()
            DedicatedRecord(spec = spec, process = process)
        } catch (throwable: Throwable) {
            host.killDedicatedProcess(handle)
            if (throwable is DeadObjectException) throw PrivilegeUnavailableException(cause = throwable)
            throw throwable
        }
    }

    private fun createEmbeddedRecord(spec: PrivilegeUserServiceSpec): Record {
        val binder = spec.serviceClassName?.let { serviceClassName ->
            val instance = PrivilegeUserServiceLoader.instantiate(
                serviceClassName = serviceClassName,
                contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                    packageName = host.packageName,
                    userId = host.userId,
                    mode = PrivilegeUserServiceLoader.ContextMode.PACKAGE_CONTEXT_ONLY,
                    contextRuntimeProvider = embeddedContextRuntimeProvider,
                ),
            )
            binderFrom(instance, serviceClassName)
        }
        return EmbeddedRecord(
            spec = spec,
            binder = binder,
        )
    }

    private fun linkConnection(
        record: Record,
        serviceId: PrivilegeUserServiceId,
        connectionId: String,
        client: IBinder,
        onDisconnected: () -> Unit,
    ) {
        val deathRecipient = IBinder.DeathRecipient {
            runCatching {
                cancelBind(connectionId, serviceId)
            }
        }
        val connection = Connection(
            serviceId = serviceId,
            record = record,
            client = client,
            deathRecipient = deathRecipient,
            onDisconnected = onDisconnected,
        )
        synchronized(stateLock) {
            connections[connectionId] = connection
        }
        record.boundCount += 1
        try {
            client.linkToDeath(deathRecipient, 0)
        } catch (exception: RemoteException) {
            synchronized(stateLock) {
                connections.remove(connectionId, connection)
            }
            record.boundCount -= 1
            throw exception
        }

        val stillLinked = synchronized(stateLock) {
            connections[connectionId] === connection
        }
        if (!stillLinked) {
            throw DeadObjectException("UserService client died while binding")
        }
    }

    private fun cancelBind(connectionId: String, serviceId: PrivilegeUserServiceId) {
        withServiceLock(serviceId) {
            unbindLockedByService(connectionId)
        }
    }

    private fun unbindLockedByService(connectionId: String) {
        val connection = synchronized(stateLock) {
            connections[connectionId]
        } ?: return
        connection.record.unbind(connectionId)
        synchronized(stateLock) { connections.remove(connectionId) }
        runCatching {
            connection.client.unlinkToDeath(connection.deathRecipient, 0)
        }
        runCatching(connection.onDisconnected)
        connection.record.boundCount -= 1
        if (!connection.record.daemon && connection.record.boundCount == 0) {
            destroyRecord(connection.serviceId, connection.record)
        }
    }

    private fun acceptBind(
        connectionId: String,
        serviceId: PrivilegeUserServiceId,
        daemon: Boolean,
        revision: Long,
    ) {
        withServiceLock(serviceId) {
            val connection = synchronized(stateLock) { connections[connectionId] }
                ?: return@withServiceLock
            connection.record.updateDaemon(daemon, revision)
        }
    }

    private fun destroyCreatedUnusedRecord(
        id: PrivilegeUserServiceId,
        ensured: EnsuredRecord,
    ) {
        if (!ensured.created || ensured.record.daemon || ensured.record.boundCount != 0) return
        val current = synchronized(stateLock) {
            records[id]
        }
        if (current === ensured.record) {
            destroyRecord(id, ensured.record)
        }
    }

    private fun destroyRecord(
        id: PrivilegeUserServiceId,
        record: Record,
    ) {
        if (detachRecord(id, record)) {
            record.destroy()
        }
    }

    private fun failRecord(
        id: PrivilegeUserServiceId,
        record: Record,
    ) {
        withServiceLock(id) {
            if (detachRecord(id, record)) {
                record.fail()
            }
        }
    }

    private fun detachRecord(
        id: PrivilegeUserServiceId,
        record: Record,
    ): Boolean {
        if (synchronized(stateLock) { records[id] !== record }) return false
        record.closeBindings()
        val removedConnections = synchronized(stateLock) {
            if (!records.remove(id, record)) {
                null
            } else {
                connections
                    .filterValues { it.record === record }
                    .keys
                    .toList()
                    .mapNotNull(connections::remove)
            }
        } ?: return false
        removedConnections.forEach { connection ->
            runCatching {
                connection.client.unlinkToDeath(connection.deathRecipient, 0)
            }
            runCatching(connection.onDisconnected)
        }
        return true
    }

    private fun serviceLock(id: PrivilegeUserServiceId): ReentrantLock =
        serviceLocks[Math.floorMod(id.hashCode(), serviceLocks.size)]

    private fun <T> withServiceLockInterruptibly(
        id: PrivilegeUserServiceId,
        block: () -> T,
    ): T {
        val lock = serviceLock(id)
        lock.lockInterruptibly()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun <T> withServiceLock(
        id: PrivilegeUserServiceId,
        block: () -> T,
    ): T {
        val lock = serviceLock(id)
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("UserService operation was cancelled")
        }
    }

    internal class BindResult internal constructor(
        val connectionId: String,
        val binder: IBinder,
        private val acceptAction: () -> Unit,
        private val cancelAction: () -> Unit,
    ) {
        internal fun accept() {
            acceptAction()
        }

        internal fun cancel() {
            cancelAction()
        }
    }

    private data class EnsuredRecord(
        val record: Record,
        val created: Boolean,
    )

    private enum class RecordState {
        RUNNING,
        DESTROYED,
        FAILED,
    }

    private abstract inner class Record(
        val spec: PrivilegeUserServiceSpec,
    ) {
        private var nextDaemonRevision = 0L
        private var appliedDaemonRevision = 0L
        var daemon: Boolean = false
            private set
        var boundCount: Int = 0
        var state: RecordState = RecordState.RUNNING

        val isRunning: Boolean
            get() = state == RecordState.RUNNING

        open val isAlive: Boolean get() = true
        abstract fun bind(connectionId: String): IBinder
        abstract fun unbind(connectionId: String)
        abstract fun closeBindings()

        abstract fun destroy()

        open fun onRegistered() = Unit

        open fun fail() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.FAILED
            boundCount = 0
        }

        fun nextDaemonRevision(): Long = ++nextDaemonRevision

        fun updateDaemon(value: Boolean, revision: Long) {
            // A delayed acknowledgement must not undo a newer accepted bind.
            if (revision <= appliedDaemonRevision) return
            appliedDaemonRevision = revision
            daemon = value
        }

        fun requireRunning(operation: String) {
            if (state != RecordState.RUNNING) {
                throw PrivilegeUnavailableException(
                    "$operation failed because UserService is $state: ${spec.serviceClassName}",
                )
            }
        }
    }

    private inner class EmbeddedRecord(
        spec: PrivilegeUserServiceSpec,
        private val binder: IBinder?,
    ) : Record(spec) {
        private val bindings = PrivilegeUserServiceBindings(binder)
        override fun bind(connectionId: String): IBinder = bindings.bind(connectionId)
        override fun unbind(connectionId: String) = bindings.unbind(connectionId)
        override fun closeBindings() = bindings.close()

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.DESTROYED
            boundCount = 0
            bindings.close()
            binder?.let(PrivilegeUserServiceDestroyer::destroy)
        }
    }

    private inner class DedicatedRecord(
        spec: PrivilegeUserServiceSpec,
        private val process: IPrivilegeUserServiceProcess,
    ) : Record(spec) {
        private val processBinder = process.asBinder()
        override val isAlive: Boolean get() = processBinder.isBinderAlive
        private var processLinked = false
        private val processDeathRecipient = IBinder.DeathRecipient {
            failRecord(spec.id(), this@DedicatedRecord)
        }

        override fun onRegistered() {
            processBinder.linkToDeath(processDeathRecipient, 0)
            processLinked = true
        }

        override fun bind(connectionId: String): IBinder {
            requireRunning("Bind dedicated UserService")
            return process.bind(connectionId)
        }

        override fun unbind(connectionId: String) = closeInProcess { process.unbind(connectionId) }

        override fun closeBindings() = closeInProcess { process.closeBindings() }

        private fun closeInProcess(action: () -> Unit) {
            try {
                action()
            } catch (_: DeadObjectException) {
                // A dead process has no callable gates left to close.
            }
        }

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            val shouldDestroyProcess = state != RecordState.FAILED
            state = RecordState.DESTROYED
            boundCount = 0
            unlinkProcessDeath()
            if (shouldDestroyProcess) {
                requestDedicatedDestroy()
            }
        }

        override fun fail() {
            super.fail()
            unlinkProcessDeath()
        }

        private fun requestDedicatedDestroy() {
            Thread {
                runCatching { process.destroy() }
            }.apply {
                name = "priv-kit-user-service-destroy-call"
                isDaemon = true
                start()
            }
        }

        private fun unlinkProcessDeath() {
            if (!processLinked) return
            processLinked = false
            runCatching {
                processBinder.unlinkToDeath(processDeathRecipient, 0)
            }
        }
    }

    private data class Connection(
        val serviceId: PrivilegeUserServiceId,
        val record: Record,
        val client: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
        val onDisconnected: () -> Unit,
    )

    companion object {
        const val DEFAULT_DEDICATED_START_TIMEOUT_MILLIS: Long = 15_000L
        private const val SERVICE_LOCK_STRIPE_COUNT: Int = 64

        internal fun binderFrom(
            instance: Any,
            serviceClassName: String,
        ): IBinder =
            when (instance) {
                is IBinder -> instance
                is IInterface -> instance.asBinder()
                else -> throw IllegalStateException(
                    "UserService must implement IBinder or IInterface: $serviceClassName",
                )
            }
    }
}
