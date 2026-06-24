package priv.kit.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeBinderClientTest {
    @Test
    fun requireMissingEndpointThrowsTypedException() {
        withServerProvider({ FakePrivilegeServer() }) {
            val client = PrivilegeBinderClient()

            assertThrows(PrivilegeBinderEndpointNotFoundException::class.java) {
                client.require()
            }
        }
    }

    @Test
    fun getWithDeadServerBinderThrowsServerDisconnectedException() {
        withServerProvider({ FakePrivilegeServer(serverBinder = FakeBinder(alive = false)) }) {
            val client = PrivilegeBinderClient()

            assertThrows(PrivilegeServerDisconnectedException::class.java) {
                client.get()
            }
        }
    }

    @Test
    fun getDeadObjectExceptionThrowsServerDisconnectedException() {
        val deadObjectException = DeadObjectException("server died")
        withServerProvider({ FakePrivilegeServer(getFailure = deadObjectException) }) {
            val client = PrivilegeBinderClient()

            val exception = assertThrows(PrivilegeServerDisconnectedException::class.java) {
                client.get()
            }

            assertSame(deadObjectException, exception.cause)
        }
    }

    @Test
    fun getRemoteExceptionThrowsRemoteCallException() {
        val remoteException = RemoteException("remote failure")
        withServerProvider({ FakePrivilegeServer(getFailure = remoteException) }) {
            val client = PrivilegeBinderClient()

            val exception = assertThrows(PrivilegeBinderRemoteCallException::class.java) {
                client.get()
            }

            assertSame(remoteException, exception.cause)
            assertTrue(exception.message?.contains("get Binder endpoint") == true)
        }
    }

    @Test
    fun registerDeadEndpointThrowsEndpointDeadException() {
        withServerProvider({ FakePrivilegeServer() }) {
            val client = PrivilegeBinderClient()

            assertThrows(PrivilegeBinderEndpointDeadException::class.java) {
                client.register(FakeBinder(alive = false))
            }
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
        private val serverBinder: IBinder = FakeBinder(),
        private val endpointBinder: IBinder? = null,
        private val getFailure: RemoteException? = null,
    ) : IPrivilegeServer {
        override fun asBinder(): IBinder = serverBinder

        override fun shutdown() = Unit

        override fun registerBinderEndpoint(binder: IBinder) = Unit

        override fun getBinderEndpoint(): IBinder? {
            getFailure?.let { throw it }
            return endpointBinder
        }

        override fun unregisterBinderEndpoint(): Boolean = false

        override fun getUserServiceManager(): IBinder? = null
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
