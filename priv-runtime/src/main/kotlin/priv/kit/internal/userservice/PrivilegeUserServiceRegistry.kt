package priv.kit.internal.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import priv.kit.userservice.PrivilegeUserServiceBindException
import priv.kit.userservice.PrivilegeUserServiceDeclarationException
import priv.kit.userservice.PrivilegeUserServiceId
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import priv.kit.userservice.PrivilegeUserServiceOwnerDeathPolicy
import priv.kit.userservice.PrivilegeUserServiceProcessMode
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStartException
import java.util.UUID

internal class PrivilegeUserServiceRegistry internal constructor(
    private val host: PrivilegeUserServiceHost,
    private val dedicatedStartTimeoutMillis: Long = DEFAULT_DEDICATED_START_TIMEOUT_MILLIS,
) {
    init {
        PrivilegeUserServiceLoader.prepareContextRuntime()
    }

    private val lock = Any()
    private val records = mutableMapOf<PrivilegeUserServiceId, Record>()
    private val connections = mutableMapOf<String, Connection>()

    internal fun start(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
    ) {
        synchronized(lock) {
            val record = ensureRecordLocked(spec)
            linkOwnerLocked(record, client)
            record.start()
        }
    }

    internal fun bind(
        spec: PrivilegeUserServiceSpec,
        client: IBinder,
    ): BindResult =
        synchronized(lock) {
            val record = ensureRecordLocked(spec)
            val connectionId = UUID.randomUUID().toString()
            val binder = record.bind(connectionId)
            linkConnectionLocked(record, connectionId, client)
            BindResult(
                connectionId = connectionId,
                binder = binder,
            )
        }

    internal fun unbind(connectionId: String) {
        synchronized(lock) {
            if (!unbindLocked(connectionId)) {
                throw PrivilegeUserServiceNotRunningException(
                    "UserService connection was not found: $connectionId",
                )
            }
        }
    }

    internal fun stop(spec: PrivilegeUserServiceSpec) {
        synchronized(lock) {
            val id = spec.id()
            val record = records[id] ?: return@synchronized
            record.started = false
            if (record.boundCount == 0) {
                destroyRecordLocked(id, record)
            }
        }
    }

    internal fun destroyOnOwnerDeath() {
        synchronized(lock) {
            records.entries
                .filter { it.value.spec.ownerDeathPolicy == PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH }
                .map { it.key to it.value }
                .forEach { (id, record) ->
                    destroyRecordLocked(id, record)
                }
        }
    }

    internal fun destroyAll() {
        synchronized(lock) {
            records.entries
                .map { it.key to it.value }
                .forEach { (id, record) ->
                    destroyRecordLocked(id, record)
                }
        }
    }

    private fun ensureRecordLocked(spec: PrivilegeUserServiceSpec): Record {
        val id = spec.id()
        val current = records[id]
        if (
            current != null &&
            current.spec.version == spec.version &&
            current.spec.processMode == spec.processMode &&
            current.spec.ownerDeathPolicy == spec.ownerDeathPolicy &&
            current.isRunning
        ) {
            current.spec = spec
            return current
        }

        if (current != null) {
            destroyRecordLocked(id, current)
        }

        val record = when (spec.processMode) {
            PrivilegeUserServiceProcessMode.DEDICATED_PROCESS -> createDedicatedRecord(spec)
            PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS -> createEmbeddedRecord(spec)
        }
        records[id] = record
        try {
            record.onRegisteredLocked()
        } catch (throwable: Throwable) {
            records.remove(id, record)
            record.destroy()
            throw throwable
        }
        return record
    }

    private fun createDedicatedRecord(spec: PrivilegeUserServiceSpec): Record {
        val token = UUID.randomUUID().toString()
        val handle = host.startDedicatedProcess(spec, token)
        val process = try {
            host.awaitDedicatedProcess(token, dedicatedStartTimeoutMillis)
        } catch (throwable: Throwable) {
            host.killDedicatedProcess(handle)
            throw PrivilegeUserServiceStartException(
                "Dedicated UserService did not report ready: ${spec.serviceClassName}",
                throwable,
            )
        }
        return DedicatedRecord(
            spec = spec,
            host = host,
            handle = handle,
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
            ),
        )
        val binder = binderFrom(instance, spec.serviceClassName)
        return EmbeddedRecord(
            spec = spec,
            binder = binder,
        )
    }

    private fun linkOwnerLocked(
        record: Record,
        owner: IBinder,
    ) {
        if (record.ownerLinked) return
        try {
            owner.linkToDeath(record.ownerDeathRecipient, 0)
            record.ownerBinder = owner
            record.ownerLinked = true
        } catch (_: RemoteException) {
            destroyRecordLocked(record.spec.id(), record)
        }
    }

    private fun linkConnectionLocked(
        record: Record,
        connectionId: String,
        client: IBinder,
    ) {
        val deathRecipient = IBinder.DeathRecipient {
            synchronized(lock) {
                unbindLocked(connectionId)
            }
        }
        try {
            client.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            record.unbind(connectionId)
            if (!record.started && record.boundCount == 0) {
                destroyRecordLocked(record.spec.id(), record)
            }
            return
        }
        connections[connectionId] = Connection(
            id = connectionId,
            record = record,
            client = client,
            deathRecipient = deathRecipient,
        )
        record.boundCount += 1
    }

    private fun unbindLocked(connectionId: String): Boolean {
        val connection = connections.remove(connectionId) ?: return false
        runCatching {
            connection.client.unlinkToDeath(connection.deathRecipient, 0)
        }
        connection.record.unbind(connectionId)
        connection.record.boundCount -= 1
        if (!connection.record.started && connection.record.boundCount == 0) {
            val spec = connection.record.spec
            destroyRecordLocked(spec.id(), connection.record)
        }
        return true
    }

    private fun destroyRecordLocked(
        id: PrivilegeUserServiceId,
        record: Record,
    ) {
        records.remove(id, record)
        unlinkConnectionsLocked(record)
        unlinkOwnerLocked(record)
        record.destroy()
    }

    private fun failRecordLocked(
        record: Record,
    ) {
        val id = record.spec.id()
        if (records[id] !== record || record.state == RecordState.DESTROYED) return
        records.remove(id, record)
        unlinkConnectionsLocked(record)
        unlinkOwnerLocked(record)
        record.fail()
    }

    private fun unlinkConnectionsLocked(record: Record) {
        connections.values
            .filter { it.record === record }
            .forEach { connection ->
                runCatching {
                    connection.client.unlinkToDeath(connection.deathRecipient, 0)
                }
                connections.remove(connection.id)
            }
    }

    private fun unlinkOwnerLocked(record: Record) {
        record.ownerBinder?.let { owner ->
            runCatching {
                owner.unlinkToDeath(record.ownerDeathRecipient, 0)
            }
        }
        record.ownerBinder = null
        record.ownerLinked = false
    }

    internal class BindResult(
        val connectionId: String,
        val binder: IBinder,
    )

    private enum class RecordState {
        RUNNING,
        DESTROYED,
        FAILED,
    }

    private abstract inner class Record(
        var spec: PrivilegeUserServiceSpec,
    ) {
        var started: Boolean = false
        var boundCount: Int = 0
        var state: RecordState = RecordState.RUNNING
        var ownerBinder: IBinder? = null
        var ownerLinked: Boolean = false

        val isRunning: Boolean
            get() = state == RecordState.RUNNING

        val ownerDeathRecipient = IBinder.DeathRecipient {
            synchronized(lock) {
                val current = records[spec.id()]
                if (current === this && spec.ownerDeathPolicy == PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH) {
                    destroyRecordLocked(spec.id(), this)
                }
            }
        }

        abstract fun start()

        abstract fun bind(connectionId: String): IBinder

        abstract fun unbind(connectionId: String)

        abstract fun destroy()

        open fun onRegisteredLocked() = Unit

        open fun fail() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.FAILED
            started = false
            boundCount = 0
        }

        fun requireRunning(operation: String) {
            if (state != RecordState.RUNNING) {
                throw PrivilegeUserServiceNotRunningException(
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

        override fun start() {
            started = true
        }

        override fun bind(connectionId: String): IBinder {
            return gate ?: PrivilegeUserServiceGateBinder(binder).also {
                gate = it
            }
        }

        override fun unbind(connectionId: String) = Unit

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            state = RecordState.DESTROYED
            gate?.close()
            PrivilegeUserServiceDestroyer.destroy(binder)
        }
    }

    private inner class DedicatedRecord(
        spec: PrivilegeUserServiceSpec,
        private val host: PrivilegeUserServiceHost,
        private val handle: Process,
        private val process: IPrivilegeUserServiceProcess,
    ) : Record(spec) {
        private val processBinder = process.asBinder()
        private var processLinked = false
        private var gate: PrivilegeUserServiceGateBinder? = null
        private val processDeathRecipient = IBinder.DeathRecipient {
            synchronized(lock) {
                failRecordLocked(record = this@DedicatedRecord)
            }
        }

        override fun onRegisteredLocked() {
            try {
                processBinder.linkToDeath(processDeathRecipient, 0)
                processLinked = true
            } catch (exception: RemoteException) {
                throw PrivilegeUserServiceStartException(
                    "Dedicated UserService process died while connecting: ${spec.serviceClassName}",
                    exception,
                )
            }
            if (!processBinder.pingBinder()) {
                unlinkProcessDeath()
                throw PrivilegeUserServiceStartException(
                    "Dedicated UserService process died while connecting: ${spec.serviceClassName}",
                )
            }
        }

        override fun start() {
            requireRunning("Start dedicated UserService")
            try {
                process.start()
                started = true
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceStartException(
                    "Dedicated UserService start failed: ${spec.serviceClassName}",
                    throwable,
                )
            }
        }

        override fun bind(connectionId: String): IBinder {
            requireRunning("Bind dedicated UserService")
            val binder = try {
                process.bind()
            } catch (throwable: Throwable) {
                throw PrivilegeUserServiceBindException(
                    "Dedicated UserService bind failed: ${spec.serviceClassName}",
                    throwable,
                )
            }
            return gate ?: PrivilegeUserServiceGateBinder(binder).also {
                gate = it
            }
        }

        override fun unbind(connectionId: String) {
            runCatching { process.unbind(connectionId) }
        }

        override fun destroy() {
            if (state == RecordState.DESTROYED) return
            val shouldDestroyProcess = state != RecordState.FAILED
            state = RecordState.DESTROYED
            started = false
            boundCount = 0
            gate?.close()
            unlinkProcessDeath()
            if (shouldDestroyProcess) {
                superviseDedicatedDestroy()
            }
        }

        override fun fail() {
            super.fail()
            gate?.close()
            unlinkProcessDeath()
        }

        private fun superviseDedicatedDestroy() {
            val timeoutMillis = spec.destroyTimeoutMillis
            val className = spec.serviceClassName
            Thread {
                Thread {
                    runCatching { process.destroy() }
                }.apply {
                    name = "priv-kit-user-service-destroy-call"
                    isDaemon = true
                    start()
                }

                if (timeoutMillis >= 0L) {
                    val exited = runCatching {
                        host.awaitDedicatedProcessExit(handle, timeoutMillis)
                    }.getOrDefault(false)
                    if (!exited) {
                        host.killDedicatedProcess(handle)
                    }
                }
            }.apply {
                name = "priv-kit-user-service-destroy-watch"
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
        val id: String,
        val record: Record,
        val client: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
    )

    companion object {
        const val DEFAULT_DEDICATED_START_TIMEOUT_MILLIS: Long = 15_000L

        internal fun binderFrom(
            instance: Any,
            serviceClassName: String,
        ): IBinder =
            when (instance) {
                is IBinder -> instance
                is IInterface -> instance.asBinder()
                else -> throw PrivilegeUserServiceDeclarationException(
                    "UserService must implement IBinder or IInterface: $serviceClassName",
                )
            }
    }
}
