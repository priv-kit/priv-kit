package priv.kit.binder

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicReference

class PrivilegeBinderRegistry {
    private val endpoint = AtomicReference<Entry?>()

    fun register(binder: IBinder) {
        if (!binder.pingBinder()) {
            throw PrivilegeBinderEndpointDeadException()
        }

        val entry = Entry(binder)
        try {
            binder.linkToDeath(entry.deathRecipient, 0)
        } catch (exception: RemoteException) {
            throw PrivilegeBinderEndpointDeadException(exception)
        }

        endpoint.getAndSet(entry)?.unlink()
    }

    fun get(): PrivilegeBinderEndpoint? {
        val entry = endpoint.get() ?: return null
        if (!entry.binder.pingBinder()) {
            if (endpoint.compareAndSet(entry, null)) {
                entry.unlink()
            }
            return null
        }
        return PrivilegeBinderEndpoint(entry.binder)
    }

    fun getBinder(): IBinder? =
        get()?.asBinder()

    fun unregister(): Boolean {
        val entry = endpoint.getAndSet(null) ?: return false
        entry.unlink()
        return true
    }

    fun clear() {
        endpoint.getAndSet(null)?.unlink()
    }

    private inner class Entry(
        val binder: IBinder,
    ) {
        private val entry = this

        val deathRecipient = IBinder.DeathRecipient {
            endpoint.compareAndSet(entry, null)
        }

        fun unlink() {
            runCatching {
                binder.unlinkToDeath(deathRecipient, 0)
            }
        }
    }
}
