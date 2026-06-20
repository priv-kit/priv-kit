package priv.kit.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeBinderRegistryTest {
    @Test
    fun returnsRegisteredEndpoint() {
        val registry = PrivilegeBinderRegistry()
        val binder = FakeBinder()

        registry.register(binder)

        assertSame(binder, registry.get()?.asBinder())
    }

    @Test
    fun registerReplacesPreviousEndpoint() {
        val registry = PrivilegeBinderRegistry()
        val first = FakeBinder()
        val second = FakeBinder()

        registry.register(first)
        registry.register(second)

        assertSame(second, registry.get()?.asBinder())
        assertFalse(first.hasDeathRecipients)
    }

    @Test
    fun unregisterRemovesEndpoint() {
        val registry = PrivilegeBinderRegistry()
        val binder = FakeBinder()

        registry.register(binder)

        assertTrue(registry.unregister())
        assertFalse(registry.unregister())
        assertNull(registry.get())
    }

    @Test
    fun binderDeathRemovesEndpoint() {
        val registry = PrivilegeBinderRegistry()
        val binder = FakeBinder()

        registry.register(binder)
        binder.die()

        assertFalse(registry.unregister())
        assertNull(registry.get())
    }

    @Test
    fun registerDeadBinderThrowsEndpointDeadException() {
        val registry = PrivilegeBinderRegistry()
        val binder = FakeBinder(alive = false)

        assertThrows(PrivilegeBinderEndpointDeadException::class.java) {
            registry.register(binder)
        }
    }

    private class FakeBinder(
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()

        val hasDeathRecipients: Boolean
            get() = deathRecipients.isNotEmpty()

        fun die() {
            alive = false
            deathRecipients.forEach { recipient ->
                recipient.binderDied()
            }
        }

        override fun getInterfaceDescriptor(): String = "fake"

        override fun pingBinder(): Boolean = alive

        override fun isBinderAlive(): Boolean = alive

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = alive

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (!alive) {
                throw RemoteException("Binder is dead")
            }
            deathRecipients += recipient
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean =
            deathRecipients.remove(recipient)
    }
}
