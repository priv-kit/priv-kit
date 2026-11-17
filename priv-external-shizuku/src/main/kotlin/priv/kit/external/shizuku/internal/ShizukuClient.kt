package priv.kit.external.shizuku.internal

import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import priv.kit.core.PrivilegeStartupException
import priv.kit.external.shizuku.PrivilegeExternalShizukuStatus

internal object ShizukuClient {
    private const val API_VERSION = 13
    private const val AUTHORIZATION_TIMEOUT_MILLIS = 60_000L
    private const val TAG = "PrivilegeExternalShizuku"

    private val connection = MutableStateFlow<Connection?>(null)
    private val authorization = Mutex()
    private val requestCodes = AtomicInteger()

    val connected: Flow<Boolean> = connection.map { it?.apiSupported == true }

    fun receiveBinder(binder: IBinder, packageName: String, isSui: Boolean) {
        val session = Session(binder, isSui)
        val next = Connection(session)
        var previous: Connection?
        do {
            previous = connection.value
            val current = previous?.session
            if (current?.binder == binder) return
            if (!isSui && current != null && (current.isSui || current.binder.pingBinder())) return
        } while (!connection.compareAndSet(previous, next))
        previous?.session?.close()

        try {
            binder.linkToDeath(session, 0)
            if (connection.value?.session === session) {
                session.service.attachApplication(
                    session,
                    Bundle().apply {
                        putString("shizuku:attach-package-name", packageName)
                        putInt("shizuku:attach-api-version", API_VERSION)
                    },
                )
            }
        } catch (exception: Exception) {
            disconnect(session)
            Log.w(TAG, "Failed to attach to Shizuku", exception)
        } finally {
            if (connection.value?.session !== session) session.close()
        }
    }

    suspend fun status(): PrivilegeExternalShizukuStatus = withContext(Dispatchers.IO) {
        readStatus(connection.value)
    }

    suspend fun requestAuthorization(): Boolean? = withContext(Dispatchers.IO) {
        authorization.withLock {
            val active = connection.value ?: return@withLock null
            when (readStatus(active)) {
                PrivilegeExternalShizukuStatus.AUTHORIZED -> return@withLock true
                PrivilegeExternalShizukuStatus.UNAVAILABLE,
                PrivilegeExternalShizukuStatus.UNSUPPORTED -> return@withLock null
                PrivilegeExternalShizukuStatus.UNAUTHORIZED -> Unit
            }

            val session = active.session
            val request = PermissionRequest(requestCodes.incrementAndGet())
            session.permissionRequest = request
            try {
                session.service.requestPermission(request.code)
                val granted = withTimeoutOrNull(AUTHORIZATION_TIMEOUT_MILLIS) {
                    select<Boolean?> {
                        session.closed.onAwait { null }
                        request.completed.onAwait { it }
                    }
                }
                if (granted == null && !session.closed.isCompleted) {
                    throw PrivilegeStartupException("Timed out waiting for Shizuku authorization")
                }
                granted
            } catch (_: DeadObjectException) {
                disconnect(session)
                null
            } finally {
                session.permissionRequest = null
            }
        }
    }

    fun requireAuthorizedSession(): Session {
        val active = connection.value
        val message = when (readStatus(active)) {
            PrivilegeExternalShizukuStatus.UNAVAILABLE -> "Shizuku is not connected"
            PrivilegeExternalShizukuStatus.UNSUPPORTED -> "Shizuku API v13 or later is required"
            PrivilegeExternalShizukuStatus.UNAUTHORIZED -> "The app is not authorized to use Shizuku"
            PrivilegeExternalShizukuStatus.AUTHORIZED -> return requireNotNull(active).session
        }
        throw PrivilegeStartupException(message)
    }

    private fun readStatus(active: Connection?): PrivilegeExternalShizukuStatus {
        val apiSupported = active?.apiSupported ?: return PrivilegeExternalShizukuStatus.UNAVAILABLE
        val status = try {
            when {
                !apiSupported -> PrivilegeExternalShizukuStatus.UNSUPPORTED
                active.session.service.checkSelfPermission() -> PrivilegeExternalShizukuStatus.AUTHORIZED
                else -> PrivilegeExternalShizukuStatus.UNAUTHORIZED
            }
        } catch (_: DeadObjectException) {
            disconnect(active.session)
            return PrivilegeExternalShizukuStatus.UNAVAILABLE
        }
        if (connection.value !== active) return PrivilegeExternalShizukuStatus.UNAVAILABLE
        return status
    }

    private fun disconnect(session: Session) {
        connection.update { if (it?.session === session) null else it }
        session.close()
    }

    internal class Session(
        val binder: IBinder,
        val isSui: Boolean,
    ) : IShizukuApplication.Stub(), IBinder.DeathRecipient {
        val service: IShizukuService = IShizukuService.Stub.asInterface(binder)
        val closed = CompletableDeferred<Unit>()

        @Volatile
        var permissionRequest: PermissionRequest? = null

        override fun bindApplication(data: Bundle) {
            val apiSupported = data.getInt("shizuku:attach-reply-version", -1) >= API_VERSION
            connection.update { active ->
                if (active?.session === this) active.copy(apiSupported = apiSupported) else active
            }
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle) {
            val request = permissionRequest ?: return
            if (request.code == requestCode) {
                request.completed.complete(data.getBoolean("shizuku:request-permission-reply-allowed"))
            }
        }

        override fun binderDied() = disconnect(this)

        fun close() {
            closed.complete(Unit)
            try {
                binder.unlinkToDeath(this, 0)
            } catch (_: NoSuchElementException) {
                // The connection was replaced before its death recipient was registered.
            }
        }
    }

    internal class PermissionRequest(val code: Int) {
        val completed = CompletableDeferred<Boolean>()
    }

    // apiSupported is null until the server replies to attachApplication.
    private data class Connection(val session: Session, val apiSupported: Boolean? = null)
}
