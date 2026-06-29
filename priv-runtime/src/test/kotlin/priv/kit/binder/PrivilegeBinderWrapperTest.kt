package priv.kit.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.internal.binder.IPrivilegeServer
import priv.kit.internal.binder.PrivilegeBinderRuntime
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeBinderWrapperTest {
    @Test
    fun serverProcessSystemServiceLinkToDeathUsesLiveServerBinder() {
        val serverBinder = FakeBinder()
        withServerProvider({ FakePrivilegeServer(serverBinder, hasSystemService = true) }) {
            val wrapper = PrivilegeBinderWrapper.fromSystemService(
                serviceName = "activity",
                source = PrivilegeSystemServiceSource.SERVER_PROCESS,
            )!!
            val recipient = IBinder.DeathRecipient { }

            wrapper.linkToDeath(recipient, 0)

            assertEquals(1, serverBinder.deathRecipientCount)
        }
    }

    @Test
    fun serverProcessSystemServiceLinkToDeathThrowsTypedExceptionWhenServerBinderIsDead() {
        val serverBinder = FakeBinder()
        withServerProvider({ FakePrivilegeServer(serverBinder, hasSystemService = true) }) {
            val wrapper = PrivilegeBinderWrapper.fromSystemService(
                serviceName = "activity",
                source = PrivilegeSystemServiceSource.SERVER_PROCESS,
            )!!
            val recipient = IBinder.DeathRecipient { }
            serverBinder.kill()

            assertThrows(PrivilegeServerDisconnectedException::class.java) {
                wrapper.linkToDeath(recipient, 0)
            }
        }
    }

    @Test
    fun pingBinderAndIsBinderAliveOnlyReportTargetBinderState() {
        withServerProvider({ throw PrivilegeServerDisconnectedException() }) {
            val liveWrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder())
            val deadWrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder(alive = false))

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
        private val hasSystemService: Boolean = false,
    ) : IPrivilegeServer {
        override fun asBinder(): IBinder = serverBinder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun hasSystemService(serviceName: String?): Boolean = hasSystemService
    }

    private class FakeBinder(
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val deathRecipientCount: Int
            get() = deathRecipients.size

        fun kill() {
            alive = false
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
