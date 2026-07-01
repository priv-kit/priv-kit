package priv.kit.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeResult
import priv.kit.internal.binder.IPrivilegeServer
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeBinderWrapperTest {
    @After
    fun clearServer() {
        runCatching { Privilege.shutdownServer() }
    }

    @Test
    fun serverProcessSystemServiceLinkToDeathUsesLiveServerBinder() {
        withServer(FakePrivilegeServer(hasSystemService = true)) { server ->
            val wrapper = PrivilegeBinderWrapper.fromSystemService(
                serviceName = "activity",
                source = PrivilegeSystemServiceSource.SERVER_PROCESS,
            )!!
            val recipient = IBinder.DeathRecipient { }

            wrapper.linkToDeath(recipient, 0)

            assertEquals(2, server.binder.deathRecipientCount)
        }
    }

    @Test
    fun serverProcessSystemServiceLinkToDeathThrowsTypedExceptionWhenServerBinderIsDead() {
        withServer(FakePrivilegeServer(hasSystemService = true)) { server ->
            val wrapper = PrivilegeBinderWrapper.fromSystemService(
                serviceName = "activity",
                source = PrivilegeSystemServiceSource.SERVER_PROCESS,
            )!!
            val recipient = IBinder.DeathRecipient { }
            server.binder.kill()

            assertThrows(PrivilegeServerDisconnectedException::class.java) {
                wrapper.linkToDeath(recipient, 0)
            }
        }
    }

    @Test
    fun pingBinderAndIsBinderAliveOnlyReportTargetBinderState() {
        val liveWrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder())
        val deadWrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder(alive = false))

        assertTrue(liveWrapper.pingBinder())
        assertTrue(liveWrapper.isBinderAlive)
        assertFalse(deadWrapper.pingBinder())
        assertFalse(deadWrapper.isBinderAlive)
    }

    private fun withServer(
        server: FakePrivilegeServer,
        block: (FakePrivilegeServer) -> Unit,
    ) {
        Privilege.connectHandshake(
            PrivilegeServerHandshakeResult(
                token = "token",
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
        )
        try {
            block(server)
        } finally {
            runCatching { Privilege.shutdownServer() }
        }
    }

    private class FakePrivilegeServer(
        private val hasSystemService: Boolean = false,
    ) : IPrivilegeServer {
        val binder = FakeBinder(localInterface = this)

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun hasSystemService(serviceName: String?): Boolean = hasSystemService
    }

    private class FakeBinder(
        private val localInterface: IInterface? = null,
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

        override fun queryLocalInterface(descriptor: String): IInterface? = localInterface

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
