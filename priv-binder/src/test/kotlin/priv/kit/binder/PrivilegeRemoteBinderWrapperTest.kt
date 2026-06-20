package priv.kit.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeRemoteBinderWrapperTest {
    @Test
    fun requireServerBinderReturnsLiveServerBinder() {
        val serverBinder = FakeBinder()
        withServerProvider({ FakePrivilegeServer(serverBinder) }) {
            val wrapper = PrivilegeRemoteBinderWrapper(FakeBinder())

            assertSame(serverBinder, wrapper.requireServerBinder())
        }
    }

    @Test
    fun requireServerBinderThrowsTypedExceptionWhenServerBinderIsDead() {
        withServerProvider({ FakePrivilegeServer(FakeBinder(alive = false)) }) {
            val wrapper = PrivilegeRemoteBinderWrapper(FakeBinder())

            assertThrows(PrivilegeServerDisconnectedException::class.java) {
                wrapper.requireServerBinder()
            }
        }
    }

    @Test
    fun pingBinderAndIsBinderAliveOnlyReportTargetBinderState() {
        withServerProvider({ throw PrivilegeServerDisconnectedException() }) {
            val liveWrapper = PrivilegeRemoteBinderWrapper(FakeBinder())
            val deadWrapper = PrivilegeRemoteBinderWrapper(FakeBinder(alive = false))

            assertTrue(liveWrapper.pingBinder())
            assertTrue(liveWrapper.isBinderAlive)
            assertFalse(deadWrapper.pingBinder())
            assertFalse(deadWrapper.isBinderAlive)
        }
    }

    private fun withServerProvider(
        provider: () -> IPrivilegeServer,
        block: () -> Unit,
    ) {
        PrivilegeBinderRuntime.installServerProvider(provider)
        try {
            block()
        } finally {
            PrivilegeBinderRuntime.clearServerProvider()
        }
    }

    private class FakePrivilegeServer(
        private val serverBinder: IBinder,
    ) : IPrivilegeServer {
        override fun asBinder(): IBinder = serverBinder

        override fun getUid(): Int = 0

        override fun getPid(): Int = 0

        override fun getLaunchMode(): Int = 0

        override fun getProtocolVersion(): Int = 0

        override fun getServerVersion(): String = "test"

        override fun updateOwnerDeathConfig(
            followDeathDelayMillis: Long,
            activeReconnectOnOwnerDeath: Boolean,
        ) = Unit

        override fun shutdown() = Unit

        override fun registerBinderEndpoint(binder: IBinder) = Unit

        override fun getBinderEndpoint(): IBinder? = null

        override fun unregisterBinderEndpoint(): Boolean = false
    }

    private class FakeBinder(
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()

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
