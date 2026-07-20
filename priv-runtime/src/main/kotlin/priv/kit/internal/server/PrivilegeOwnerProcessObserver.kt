package priv.kit.internal.server

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.userservice.PrivilegeUserServiceLoader
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PrivilegeOwnerProcessObserver internal constructor(
    private val ownerProcessStartedUri: Uri,
    private val registrar: Registrar,
    private val onOwnerProcessStarted: () -> Unit,
) : Closeable {
    internal constructor(
        packageName: String,
        userId: Int,
        onOwnerProcessStarted: () -> Unit,
    ) : this(
        ownerProcessStartedUri = PrivilegeHandshakeContract.ownerProcessStartedUri(packageName),
        registrar = PackageContextRegistrar(packageName, userId),
        onOwnerProcessStarted = onOwnerProcessStarted,
    )

    private val lifecycleLock = Any()
    private val registered = AtomicBoolean(false)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(
            selfChange: Boolean,
            uri: Uri?,
        ) {
            if (registered.get()) {
                onOwnerProcessStarted()
            }
        }
    }

    fun register(): Boolean = synchronized(lifecycleLock) {
        if (registered.get()) {
            true
        } else {
            registered.set(true)
            runCatching {
                registrar.register(ownerProcessStartedUri, observer)
                Log.i(TAG, "Observing owner process starts at $ownerProcessStartedUri")
                true
            }.getOrElse { throwable ->
                registered.set(false)
                Log.w(
                    TAG,
                    "Owner process observer registration failed; falling back to process polling",
                    throwable,
                )
                false
            }
        }
    }

    override fun close(): Unit = synchronized(lifecycleLock) {
        if (!registered.getAndSet(false)) {
            return@synchronized
        }
        runCatching {
            registrar.unregister(observer)
        }.onFailure { throwable ->
            Log.w(TAG, "Owner process observer could not be unregistered", throwable)
        }
    }

    internal interface Registrar {
        fun register(
            uri: Uri,
            observer: ContentObserver,
        )

        fun unregister(observer: ContentObserver)
    }

    private class PackageContextRegistrar(
        private val packageName: String,
        private val userId: Int,
    ) : Registrar {
        private val contentResolver by lazy {
            PrivilegeUserServiceLoader.createPackageContext(
                packageName = packageName,
                userId = userId,
            ).contentResolver
        }

        override fun register(
            uri: Uri,
            observer: ContentObserver,
        ) {
            contentResolver.registerContentObserver(uri, false, observer)
        }

        override fun unregister(observer: ContentObserver) {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    private companion object {
        private const val TAG = "PrivKitServer"
    }
}

internal class PrivilegeOwnerProcessSignal {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var sequence = 0L
    private var acknowledgedSequence = 0L

    fun signal(ownerIsAlive: Boolean = false): Long = lock.withLock {
        sequence += 1
        if (ownerIsAlive) {
            acknowledgedSequence = sequence
        }
        changed.signalAll()
        sequence
    }

    fun snapshot(): Long = lock.withLock {
        sequence
    }

    fun acknowledgeCurrent(): Long = lock.withLock {
        acknowledgedSequence = sequence
        sequence
    }

    fun acknowledgedSnapshot(): Long = lock.withLock {
        acknowledgedSequence
    }

    fun awaitNext(
        afterSequence: Long,
        timeoutMillis: Long,
    ): Long? {
        require(timeoutMillis >= 0L) { "timeoutMillis must not be negative" }
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return lock.withLock {
            while (sequence <= afterSequence) {
                if (remainingNanos <= 0L) {
                    return@withLock null
                }
                remainingNanos = changed.awaitNanos(remainingNanos)
            }
            sequence
        }
    }
}
