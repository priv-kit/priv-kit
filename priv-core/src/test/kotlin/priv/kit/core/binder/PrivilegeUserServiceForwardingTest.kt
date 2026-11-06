package priv.kit.core.binder

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.internal.userservice.PrivilegeUserServiceRegistry
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceForwardingTest {
    @Test
    fun selectedEndpointForwardsParcelsBindersFileDescriptorsAndRepliesWithoutMainServer(): Unit = runBlocking {
        val token = Binder()
        val pipe = ParcelFileDescriptor.createPipe()
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(42, code)
                data.enforceInterface("test.target")
                assertEquals("argument", data.readString())
                assertSame(token, data.readStrongBinder())
                data.readFileDescriptor().use { assertNotNull(it) }
                reply!!.writeNoException()
                reply.writeString("result")
                return true
            }
        }
        val forwarder = PrivilegeBinderForwarder { name -> if (name == "test") target else null }
        val connection = connection(forwarder)
        val wrapper = PrivilegeBinderWrapper.fromSystemService("test", userService = connection)!!
        assertNull(PrivilegeBinderWrapper.fromSystemService("missing", userService = connection))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("test.target")
            data.writeString("argument")
            data.writeStrongBinder(token)
            data.writeFileDescriptor(pipe[1].fileDescriptor)
            assertTrue(wrapper.transact(42, data, reply, 0))
            reply.readException()
            assertEquals("result", reply.readString())
            connection.unbind()
            assertFalse(wrapper.isBinderAlive)
            assertThrows(DeadObjectException::class.java) { wrapper.transact(42, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
            pipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun fixedBinderPreservesFalseAndOneWayFlags() {
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(7, data.readInt())
                return false
            }
        }
        val wrapper = PrivilegeBinderWrapper.fromBinder(target, connection(PrivilegeBinderForwarder()))
        val data = Parcel.obtain()
        try {
            data.writeInt(7)
            assertFalse(wrapper.transact(42, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }

    @Test
    fun builtInServiceNeedsNoClassAndUnbindingOneConnectionDoesNotInvalidateAnother() {
        val registry = PrivilegeUserServiceRegistry(
            TestEmbeddedUserServiceHost(),
            embeddedContextRuntimeProvider = { error("No context needed") },
        )
        val spec = PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.EMBEDDED)
        val first = registry.bind(spec, TestBinder())
        val second = registry.bind(spec, TestBinder())
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        }
        val firstWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(first.forwardingBinder))
        val secondWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(second.forwardingBinder))
        val data = Parcel.obtain()
        try {
            assertTrue(firstWrapper.transact(42, data, null, 0))
            registry.unbind(first.connectionId)
            assertThrows(DeadObjectException::class.java) { firstWrapper.transact(42, data, null, 0) }
            assertTrue(secondWrapper.transact(42, data, null, 0))
            registry.destroyAll()
            assertThrows(DeadObjectException::class.java) { secondWrapper.transact(42, data, null, 0) }
        } finally { data.recycle() }
    }

    @Test
    fun processConfigurationRoundTripsIncludingNullAndRoot() {
        for (uid in listOf(null, -1, 0, 1000, 2000)) {
            val spec = PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess(uid))
            assertEquals(spec, PrivilegeUserServiceContract.specFrom(PrivilegeUserServiceContract.requestBundle(spec)))
        }
        assertThrows(IllegalArgumentException::class.java) { PrivilegeUserServiceProcess(-2) }
        assertEquals("android", connection(Binder(), 1000).callingPackageName)
        assertEquals("com.android.shell", connection(Binder(), 2000).callingPackageName)
        assertNull(connection(Binder(), 0).callingPackageName)
    }

    private fun connection(forwarder: IBinder, uid: Int = 2000) = PrivilegeUserServiceConnection(
        binder = Binder(), forwardingBinder = forwarder, lifecycleBinder = forwarder, uid = uid,
        unbindAction = {},
    )
}
