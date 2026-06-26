package priv.kit.runtime

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderEndpointDeadException
import priv.kit.binder.PrivilegeBinderEndpointNotFoundException
import priv.kit.binder.PrivilegeBinderRemoteCallException
import priv.kit.binder.PrivilegeServerDisconnectedException

class PrivilegeBinderClientTest {
    @Test
    fun requireMissingEndpointThrowsTypedException() {
        val client = PrivilegeBinderClient { FakePrivilegeServer() }

        assertThrows(PrivilegeBinderEndpointNotFoundException::class.java) {
            client.require()
        }
    }

    @Test
    fun getWithDeadServerBinderThrowsServerDisconnectedException() {
        val client = PrivilegeBinderClient {
            FakePrivilegeServer(serverBinder = FakeBinder(alive = false))
        }

        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            client.get()
        }
    }

    @Test
    fun getDeadObjectExceptionThrowsServerDisconnectedException() {
        val deadObjectException = DeadObjectException("server died")
        val client = PrivilegeBinderClient {
            FakePrivilegeServer(getFailure = deadObjectException)
        }

        val exception = assertThrows(PrivilegeServerDisconnectedException::class.java) {
            client.get()
        }

        assertSame(deadObjectException, exception.cause)
    }

    @Test
    fun getRemoteExceptionThrowsRemoteCallException() {
        val remoteException = RemoteException("remote failure")
        val client = PrivilegeBinderClient {
            FakePrivilegeServer(getFailure = remoteException)
        }

        val exception = assertThrows(PrivilegeBinderRemoteCallException::class.java) {
            client.get()
        }

        assertSame(remoteException, exception.cause)
        assertTrue(exception.message?.contains("get Binder endpoint") == true)
    }

    @Test
    fun registerDeadEndpointThrowsEndpointDeadException() {
        val client = PrivilegeBinderClient { FakePrivilegeServer() }

        assertThrows(PrivilegeBinderEndpointDeadException::class.java) {
            client.register(FakeBinder(alive = false))
        }
    }

    @Test
    fun registrationCloseUnregistersOnce() {
        val server = FakePrivilegeServer()
        val client = PrivilegeBinderClient { server }

        val registration = client.register(FakeBinder())
        registration.close()
        registration.close()

        assertEquals(1, server.unregisterCalls)
    }

    private class FakePrivilegeServer(
        private val serverBinder: IBinder = FakeBinder(),
        private val endpointBinder: IBinder? = null,
        private val getFailure: RemoteException? = null,
    ) : IPrivilegeServer {
        var unregisterCalls = 0
            private set

        override fun asBinder(): IBinder = serverBinder

        override fun shutdown() = Unit

        override fun registerBinderEndpoint(binder: IBinder) = Unit

        override fun getBinderEndpoint(): IBinder? {
            getFailure?.let { throw it }
            return endpointBinder
        }

        override fun unregisterBinderEndpoint(): Boolean {
            unregisterCalls += 1
            return true
        }

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
