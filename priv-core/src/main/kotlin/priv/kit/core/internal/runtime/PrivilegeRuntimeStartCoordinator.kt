package priv.kit.core.internal.runtime

import android.os.SystemClock
import androidx.annotation.RestrictTo
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public data class PrivilegeRuntimeConnectionEvent public constructor(
    public val serverInfo: PrivilegeServerInfo,
    public val origin: PrivilegeRuntimeConnectionOrigin,
    public val clientStartOperationId: Long?,
    public val launchCorrelationId: String?,
)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public enum class PrivilegeRuntimeConnectionOrigin {
    INITIAL_LAUNCH,
    OWNER_RECONNECT,
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeRuntimeStartPreflight internal constructor(
    internal val stateSerial: Long,
    public val remainingReconnectGraceMillis: Long,
)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeRuntimeStartLease internal constructor(
    public val operationId: Long,
    private val release: (Long) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        check(closed.compareAndSet(false, true)) {
            "Runtime start lease is already closed"
        }
        release(operationId)
    }
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeRuntimeClientLaunch internal constructor(
    internal val operationId: Long,
    public val launchCorrelationId: String,
)

/**
 * Internal cross-artifact coordination used by priv-core and priv-ui.
 *
 * This type is public only because Kotlin `internal` cannot cross the runtime/UI artifact boundary.
 * It is not a supported integration API.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public object PrivilegeRuntimeStartCoordinator {
    private val arbiter = PrivilegeRuntimeStartArbiter(
        elapsedRealtime = SystemClock::elapsedRealtime,
    )
    private val mutableServerHandshakeAcceptedEvents =
        MutableSharedFlow<PrivilegeRuntimeConnectionOrigin>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    public val serverConnectionEvents: SharedFlow<PrivilegeRuntimeConnectionEvent>
        get() {
            Privilege.initializeRuntimeConnection()
            return Privilege.serverConnectionEvents
        }

    public val serverHandshakeAcceptedEvents: SharedFlow<PrivilegeRuntimeConnectionOrigin> =
        mutableServerHandshakeAcceptedEvents.asSharedFlow()

    public fun beginPreflight(): PrivilegeRuntimeStartPreflight =
        arbiter.beginPreflight()

    public fun tryCommitClientStart(
        preflight: PrivilegeRuntimeStartPreflight,
    ): PrivilegeRuntimeStartLease? {
        val alreadyConnected = runCatching { Privilege.pingServer() }.getOrDefault(false)
        if (alreadyConnected) return null
        return arbiter.tryCommitClientStart(preflight)?.let { operationId ->
            PrivilegeRuntimeStartLease(
                operationId = operationId,
                release = ::finishClientStart,
            )
        }
    }

    public fun beginClientLaunch(
        lease: PrivilegeRuntimeStartLease,
    ): PrivilegeRuntimeClientLaunch? {
        val launchCorrelationId = newLaunchCorrelationId()
        return if (arbiter.beginClientLaunch(lease.operationId, launchCorrelationId)) {
            PrivilegeRuntimeClientLaunch(
                operationId = lease.operationId,
                launchCorrelationId = launchCorrelationId,
            )
        } else {
            null
        }
    }

    public suspend fun startRoot(
        launch: PrivilegeRuntimeClientLaunch,
        timeoutMillis: Long,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo = Privilege.startRootWithLaunchCorrelationId(
        launchCorrelationId = launch.launchCorrelationId,
        timeoutMillis = timeoutMillis,
        startupLogListener = startupLogListener,
    )

    public suspend fun startAdb(
        launch: PrivilegeRuntimeClientLaunch,
        options: PrivilegeAdbStartOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo = Privilege.startAdbWithLaunchCorrelationId(
        launchCorrelationId = launch.launchCorrelationId,
        options = options,
        timeoutMillis = timeoutMillis,
        adbDeviceName = adbDeviceName,
        startupLogListener = startupLogListener,
    )

    public fun createShellStartCommand(
        launch: PrivilegeRuntimeClientLaunch,
    ): String = Privilege.createShellStartCommandWithLaunchCorrelationId(launch.launchCorrelationId)

    internal fun markOwnerProcessStarted() {
        arbiter.markOwnerProcessStarted(RECONNECT_GRACE_MILLIS)
    }

    internal fun markServerConnected() {
        arbiter.markServerConnected()
    }

    internal fun markServerDisconnected() {
        arbiter.markServerDisconnected()
    }

    internal fun tryAcceptHandshake(
        origin: PrivilegeServerHandshakeOrigin,
        launchCorrelationId: String?,
    ): PrivilegeRuntimeHandshakeTicket? =
        Privilege.withServerConnectionLock {
            arbiter.tryAcceptHandshake(origin, launchCorrelationId)
        }

    internal fun finishHandshake(ticket: PrivilegeRuntimeHandshakeTicket) {
        if (arbiter.finishHandshake(ticket)) {
            notifyOwnerReconnect()
        }
    }

    internal fun notifyServerHandshakeAccepted(ticket: PrivilegeRuntimeHandshakeTicket) {
        val origin = when (ticket.origin) {
            PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH ->
                PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH
            PrivilegeServerHandshakeOrigin.OWNER_RECONNECT ->
                PrivilegeRuntimeConnectionOrigin.OWNER_RECONNECT
        }
        mutableServerHandshakeAcceptedEvents.tryEmit(origin)
    }

    private fun finishClientStart(operationId: Long) {
        if (!arbiter.finishClientStart(operationId)) return
        notifyOwnerReconnect()
    }

    private fun notifyOwnerReconnect() {
        runCatching { PrivilegeContext.require() }
            .getOrNull()
            ?.let(PrivilegeOwnerProcessNotifier::schedule)
    }

    internal const val RECONNECT_GRACE_MILLIS: Long = 1_000L

    internal fun newLaunchCorrelationId(): String = UUID.randomUUID().toString()
}

internal data class PrivilegeRuntimeHandshakeTicket(
    val serial: Long,
    val origin: PrivilegeServerHandshakeOrigin,
    val clientStartOperationId: Long?,
)

internal class PrivilegeRuntimeStartArbiter(
    private val elapsedRealtime: () -> Long,
) {
    private val lock = Any()
    private var stateSerial = 0L
    private var nextClientStartOperationId = 0L
    private var activeClientStartOperationId: Long? = null
    private var activeLaunchCorrelationId: String? = null
    private var serverConnected = false
    private var ownerReconnectDeferred = false
    private var reconnectGraceDeadlineMillis = 0L
    private var handshakeInFlightCount = 0

    fun markOwnerProcessStarted(graceMillis: Long) {
        require(graceMillis >= 0L) { "graceMillis must not be negative" }
        synchronized(lock) {
            reconnectGraceDeadlineMillis = elapsedRealtime() + graceMillis
            stateSerial += 1L
        }
    }

    fun beginPreflight(): PrivilegeRuntimeStartPreflight =
        synchronized(lock) {
            PrivilegeRuntimeStartPreflight(
                stateSerial = stateSerial,
                remainingReconnectGraceMillis =
                    (reconnectGraceDeadlineMillis - elapsedRealtime()).coerceAtLeast(0L),
            )
        }

    fun tryCommitClientStart(preflight: PrivilegeRuntimeStartPreflight): Long? =
        synchronized(lock) {
            if (
                serverConnected ||
                activeClientStartOperationId != null ||
                handshakeInFlightCount != 0 ||
                elapsedRealtime() < reconnectGraceDeadlineMillis ||
                preflight.stateSerial != stateSerial
            ) {
                return null
            }
            nextClientStartOperationId += 1L
            val operationId = nextClientStartOperationId
            activeClientStartOperationId = operationId
            stateSerial += 1L
            operationId
        }

    /** Returns whether a reconnect rejected by this lease should be signalled again. */
    fun finishClientStart(operationId: Long): Boolean =
        synchronized(lock) {
            if (activeClientStartOperationId != operationId) return false
            val notifyDeferredReconnect = ownerReconnectDeferred && !serverConnected
            activeClientStartOperationId = null
            activeLaunchCorrelationId = null
            ownerReconnectDeferred = false
            stateSerial += 1L
            notifyDeferredReconnect
        }

    fun markServerConnected() {
        synchronized(lock) {
            ownerReconnectDeferred = false
            if (serverConnected) return
            serverConnected = true
            stateSerial += 1L
        }
    }

    fun markServerDisconnected() {
        synchronized(lock) {
            if (!serverConnected) return
            serverConnected = false
            stateSerial += 1L
        }
    }

    fun beginClientLaunch(
        operationId: Long,
        launchCorrelationId: String,
    ): Boolean {
        require(launchCorrelationId.isNotBlank()) { "launchCorrelationId must not be blank" }
        return synchronized(lock) {
            if (activeClientStartOperationId != operationId) return false
            activeLaunchCorrelationId = launchCorrelationId
            stateSerial += 1L
            true
        }
    }

    fun tryAcceptHandshake(
        origin: PrivilegeServerHandshakeOrigin,
        launchCorrelationId: String?,
    ): PrivilegeRuntimeHandshakeTicket? =
        synchronized(lock) {
            if (serverConnected) return null
            val activeOperationId = activeClientStartOperationId
            if (
                origin == PrivilegeServerHandshakeOrigin.OWNER_RECONNECT &&
                activeOperationId != null
            ) {
                ownerReconnectDeferred = true
                return null
            }
            if (handshakeInFlightCount != 0) {
                if (origin == PrivilegeServerHandshakeOrigin.OWNER_RECONNECT) {
                    ownerReconnectDeferred = true
                }
                return null
            }
            if (
                origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH &&
                activeOperationId != null &&
                launchCorrelationId != activeLaunchCorrelationId
            ) {
                return null
            }
            stateSerial += 1L
            handshakeInFlightCount += 1
            PrivilegeRuntimeHandshakeTicket(
                serial = stateSerial,
                origin = origin,
                clientStartOperationId = activeOperationId.takeIf {
                    origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH
                },
            )
        }

    /** Returns whether an OWNER_RECONNECT rejected behind this handshake should retry. */
    fun finishHandshake(ticket: PrivilegeRuntimeHandshakeTicket): Boolean =
        synchronized(lock) {
            check(handshakeInFlightCount > 0) {
                "No runtime handshake is in flight"
            }
            handshakeInFlightCount -= 1
            stateSerial = maxOf(stateSerial, ticket.serial) + 1L
            val notifyDeferredReconnect =
                handshakeInFlightCount == 0 &&
                    ownerReconnectDeferred &&
                    activeClientStartOperationId == null &&
                    !serverConnected
            if (notifyDeferredReconnect) {
                ownerReconnectDeferred = false
            }
            notifyDeferredReconnect
        }
}
