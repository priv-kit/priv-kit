package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import priv.kit.core.userservice.PrivilegeUserServiceException
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

    internal fun start(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
    ): StartResult {
        val id = spec.id()
        return withServiceLockInterruptibly(id) {
            val ensured = ensureRecord(spec)
            val mutation = ensured.record.beginStarted()
            try {
                ensureNotInterrupted()
                linkOwner(ensured.record, client)
                ensured.record.start()
                ensureNotInterrupted()
                StartResult(
                    acceptAction = { acceptStart(id, ensured.record, mutation) },
                    rollbackAction = { rollbackStart(id, ensured.record, mutation) },
                )
            } catch (throwable: Throwable) {
                ensured.record.rollbackStarted(mutation)
                destroyCreatedUnusedRecord(id, ensured)
                throw throwable
            }
        }
    }

    internal fun bind(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
    ): BindResult {
        val id = spec.id()
        return withServiceLockInterruptibly(id) {
            val ensured = ensureRecord(spec)
            val connectionId = UUID.randomUUID().toString()
            var linked = false
            var lifecycleMutation: LifecycleMutation? = null
            try {
                ensureNotInterrupted()
                lifecycleMutation = if (ensured.record.spec.daemon) {
                    ensured.record.beginStarted()
                } else {
                    null
                }
                val binder = ensured.record.bind()
                ensureNotInterrupted()
                linkConnection(
                    record = ensured.record,
                    serviceId = id,
                    connectionId = connectionId,
                    client = client,
                    lifecycleMutation = lifecycleMutation,
                )
                linked = true
                ensureNotInterrupted()
                BindResult(
                    connectionId = connectionId,
                    binder = binder,
                    acceptAction = {
                        lifecycleMutation?.let { mutation ->
                            acceptStart(id, ensured.record, mutation)
                        }
                    },
                    cancelAction = {
                        cancelBind(
                            connectionId = connectionId,
                            serviceId = id,
                            record = ensured.record,
                            lifecycleMutation = lifecycleMutation,
                        )
                    },
                )
            } catch (throwable: Throwable) {
                if (linked) {
                    unbindLockedByService(connectionId, cancelLifecycle = true)
                } else {
                    lifecycleMutation?.let(ensured.record::rollbackStarted)
                }
                destroyCreatedUnusedRecord(id, ensured)
                throw throwable
            }
        }
    }

    internal fun unbind(connectionId: String) {
        val connection = synchronized(stateLock) {
            connections[connectionId]
        } ?: return
        withServiceLockInterruptibly(connection.serviceId) {
            unbindLockedByService(
                connectionId = connectionId,
                cancelLifecycle = false,
            )
        }
    }

    internal fun stop(spec: PrivilegeUserServiceSpec) {
        val id = spec.id()
        withServiceLockInterruptibly(id) {
            val record = synchronized(stateLock) {
                records[id]
            } ?: return@withServiceLockInterruptibly
            record.stopStarted()
            if (record.boundCount == 0) {
                destroyRecord(id, record)
            }
        }
    }

    internal fun destroyOnOwnerDeath() {
        val ids = synchronized(stateLock) {
            records.filterValues { !it.spec.daemon }.keys.toList()
        }
        ids.forEach { id ->
            withServiceLock(id) {
                val record = synchronized(stateLock) {
                    records[id]
                } ?: return@withServiceLock
                if (!record.spec.daemon) {
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
            current.spec.version == spec.version &&
            current.spec.embedded == spec.embedded &&
            current.spec.daemon == spec.daemon &&
            current.isRunning
        ) {
            current.spec = spec
            return EnsuredRecord(current, created = false)
        }

        if (current != null) {
            destroyRecord(id, current)
        }

        ensureNotInterrupted()
        val record = if (spec.embedded) createEmbeddedRecord(spec) else createDedicatedRecord(spec)
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
            throw throwable
        }
        return EnsuredRecord(record, created = true)
    }

    private fun createDedicatedRecord(spec: PrivilegeUserServiceSpec): Record {
        val token = UUID.randomUUID().toString()
        val handle = try {
            host.startDedicatedProcess(spec, token)
        } catch (throwable: Throwable) {
            throw PrivilegeUserServiceException(
                "Dedicated UserService start failed: ${spec.serviceClassName}",
                throwable,
            )
        }
        val process = try {
            host.awaitDedicatedProcess(token, dedicatedStartTimeoutMillis).also {
                ensureNotInterrupted()
            }
        } catch (throwable: Throwable) {
            host.killDedicatedProcess(handle)
            if (throwable is InterruptedException) throw throwable
            throw PrivilegeUserServiceException(
                "Dedicated UserService did not report ready: ${spec.serviceClassName}",
                throwable,
            )
        }
        return DedicatedRecord(
            spec = spec,
            process = process,
        )
    }

    private fun createEmbeddedRecord(spec: PrivilegeUserServiceSpec): Record {
        val instance = PrivilegeUserServiceLoader.instantiate(
            serviceClassName = spec.serviceClassName,
            contextConfig = PrivilegeUserServiceLoader.ContextConfig(
                packageName = host.packageName,
                userId = host.userId,
                mode = PrivilegeUserServiceLoader.ContextMode.PACKAGE_CONTEXT_ONLY,
                contextRuntimeProvider = embeddedContextRuntimeProvider,
            ),
        )
        val binder = binderFrom(instance, spec.serviceClassName)
        return EmbeddedRecord(
            spec = spec,
            binder = binder,
        )
    }

    private fun linkOwner(
        record: Record,
        owner: IBinder,
    ) {
        if (record.ownerBinder != null) return
        record.ownerBinder = owner
        try {
            owner.linkToDeath(record.ownerDeathRecipient, 0)
        } catch (exception: RemoteException) {
            record.ownerBinder = null
            destroyRecord(record.spec.id(), record)
            throw PrivilegeUserServiceException("UserService owner died while linking", exception)
        }
    }

    private fun linkConnection(
        record: Record,
        serviceId: PrivilegeUserServiceId,
        connectionId: String,
        client: IBinder,
        lifecycleMutation: LifecycleMutation?,
    ) {
        val deathRecipient = IBinder.DeathRecipient {
            runCatching {
                cancelBind(connectionId, serviceId, record, lifecycleMutation)
            }
        }
        val connection = Connection(
            serviceId = serviceId,
            record = record,
            client = client,
            deathRecipient = deathRecipient,
            lifecycleMutation = lifecycleMutation,
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
            throw PrivilegeUserServiceException("UserService client died while linking", exception)
        }

        val stillLinked = synchronized(stateLock) {
            connections[connectionId] === connection
        }
        if (!stillLinked) {
            throw PrivilegeUserServiceException("UserService client died while binding")
        }
    }

    private fun cancelBind(
        connectionId: String,
        serviceId: PrivilegeUserServiceId,
        record: Record,
        lifecycleMutation: LifecycleMutation?,
    ) {
        withServiceLock(serviceId) {
            val connection = synchronized(stateLock) {
                connections[connectionId]
            }
            if (connection != null) {
                unbindLockedByService(
                    connectionId = connectionId,
                    cancelLifecycle = true,
                )
            } else {
                val current = synchronized(stateLock) {
                    records[serviceId]
                }
                if (current === record) {
                    lifecycleMutation?.let(record::rollbackStarted)
                    if (!record.started && record.boundCount == 0) {
                        destroyRecord(serviceId, record)
                    }
                }
            }
        }
    }

    private fun unbindLockedByService(
        connectionId: String,
        cancelLifecycle: Boolean,
    ) {
        val connection = synchronized(stateLock) {
            connections.remove(connectionId)
        } ?: return
        runCatching {
            connection.client.unlinkToDeath(connection.deathRecipient, 0)
        }
        connection.record.boundCount -= 1
        if (cancelLifecycle) {
            connection.lifecycleMutation?.let(connection.record::rollbackStarted)
        }
        if (!connection.record.started && connection.record.boundCount == 0) {
            destroyRecord(connection.serviceId, connection.record)
        }
    }

    private fun rollbackStart(
        id: PrivilegeUserServiceId,
        record: Record,
        mutation: LifecycleMutation,
    ) {
        withServiceLock(id) {
            val current = synchronized(stateLock) {
                records[id]
            }
            if (current !== record) return@withServiceLock
            record.rollbackStarted(mutation)
            if (!record.started && record.boundCount == 0) {
                destroyRecord(id, record)
            }
        }
    }

    private fun acceptStart(
        id: PrivilegeUserServiceId,
        record: Record,
        mutation: LifecycleMutation,
    ) {
        val current = synchronized(stateLock) {
            records[id]
        }
        if (current === record) {
            record.acceptStarted(mutation)
        }
    }

    private fun destroyCreatedUnusedRecord(
        id: PrivilegeUserServiceId,
        ensured: EnsuredRecord,
    ) {
        if (!ensured.created || ensured.record.started || ensured.record.boundCount != 0) return
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
        }
        unlinkOwner(record)
        return true
    }

    private fun unlinkOwner(record: Record) {
        record.ownerBinder?.let { owner ->
            runCatching {
                owner.unlinkToDeath(record.ownerDeathRecipient, 0)
            }
        }
        record.ownerBinder = null
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

    internal class StartResult internal constructor(
        private val acceptAction: () -> Unit,
        private val rollbackAction: () -> Unit,
    ) {
        internal fun accept() {
            acceptAction()
        }

        internal fun rollback() {
            rollbackAction()
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

    private data class LifecycleMutation(
        val id: Long,
    )

    private enum class RecordState {
        RUNNING,
        DESTROYED,
        FAILED,
    }

    private abstract inner class Record(
        var spec: PrivilegeUserServiceSpec,
    ) {
        private val lifecycleLock = Any()
        private var committedStarted: Boolean = false
        private var nextStartMutationId: Long = 0L
        private val pendingStartMutations = mutableSetOf<Long>()
        var boundCount: Int = 0
        var state: RecordState = RecordState.RUNNING
        var ownerBinder: IBinder? = null

        val isRunning: Boolean
            get() = state == RecordState.RUNNING

        val started: Boolean
            get() = synchronized(lifecycleLock) {
                committedStarted || pendingStartMutations.isNotEmpty()
            }

        val ownerDeathRecipient = IBinder.DeathRecipient {
            val id = spec.id()
            withServiceLock(id) {
                val current = synchronized(stateLock) {
                    records[id]
                }
                if (current === this && !spec.daemon) {
                    destroyRecord(id, this)
                }
            }
        }

        abstract fun start()

        abstract fun bind(): IBinder

        abstract fun destroy()

        open fun onRegistered() = Unit

        open fun fail() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.FAILED
            stopStarted()
            boundCount = 0
        }

        fun beginStarted(): LifecycleMutation =
            synchronized(lifecycleLock) {
                val mutation = LifecycleMutation(id = ++nextStartMutationId)
                pendingStartMutations += mutation.id
                mutation
            }

        fun acceptStarted(mutation: LifecycleMutation) {
            synchronized(lifecycleLock) {
                if (pendingStartMutations.remove(mutation.id)) {
                    committedStarted = true
                }
            }
        }

        fun rollbackStarted(mutation: LifecycleMutation) {
            synchronized(lifecycleLock) {
                pendingStartMutations.remove(mutation.id)
            }
        }

        fun stopStarted() {
            synchronized(lifecycleLock) {
                committedStarted = false
                pendingStartMutations.clear()
            }
        }

        fun requireRunning(operation: String) {
            if (state != RecordState.RUNNING) {
                throw PrivilegeUserServiceException(
                    "$operation failed because UserService is $state: ${spec.serviceClassName}",
                )
            }
        }
    }

    private inner class EmbeddedRecord(
        spec: PrivilegeUserServiceSpec,
        private val binder: IBinder,
    ) : Record(spec) {
        private var gate: PrivilegeUserServiceGateBinder? = null

        override fun start() = Unit

        override fun bind(): IBinder =
            gate ?: PrivilegeUserServiceGateBinder(binder).also {
                gate = it
            }

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.DESTROYED
            stopStarted()
            boundCount = 0
            gate?.close()
            PrivilegeUserServiceDestroyer.destroy(binder)
        }
    }

    private inner class DedicatedRecord(
        spec: PrivilegeUserServiceSpec,
        private val process: IPrivilegeUserServiceProcess,
    ) : Record(spec) {
        private val processBinder = process.asBinder()
        private var processLinked = false
        private var gate: PrivilegeUserServiceGateBinder? = null
        private val processDeathRecipient = IBinder.DeathRecipient {
            failRecord(spec.id(), this@DedicatedRecord)
        }

        override fun onRegistered() {
            try {
                processBinder.linkToDeath(processDeathRecipient, 0)
                processLinked = true
            } catch (exception: RemoteException) {
                throw PrivilegeUserServiceException(
                    "Dedicated UserService process died while connecting: ${spec.serviceClassName}",
                    exception,
                )
            }
            if (!processBinder.pingBinder()) {
                unlinkProcessDeath()
                throw PrivilegeUserServiceException(
                    "Dedicated UserService process died while connecting: ${spec.serviceClassName}",
                )
            }
        }

        override fun start() {
            requireRunning("Start dedicated UserService")
            try {
                process.start()
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceException(
                    "Dedicated UserService start failed: ${spec.serviceClassName}",
                    throwable,
                )
            }
        }

        override fun bind(): IBinder {
            requireRunning("Bind dedicated UserService")
            val binder = try {
                process.bind()
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceException(
                    "Dedicated UserService bind failed: ${spec.serviceClassName}",
                    throwable,
                )
            }
            return gate ?: PrivilegeUserServiceGateBinder(binder).also {
                gate = it
            }
        }

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            val shouldDestroyProcess = state != RecordState.FAILED
            state = RecordState.DESTROYED
            stopStarted()
            boundCount = 0
            gate?.close()
            unlinkProcessDeath()
            if (shouldDestroyProcess) {
                requestDedicatedDestroy()
            }
        }

        override fun fail() {
            super.fail()
            gate?.close()
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
        val lifecycleMutation: LifecycleMutation?,
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
                else -> throw PrivilegeUserServiceException(
                    "UserService must implement IBinder or IInterface: $serviceClassName",
                )
            }
    }
}
