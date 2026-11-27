package priv.kit.core.internal.userservice

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.binder.PrivilegeSystemServiceSource
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceBindingsTest {
    private val target = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeNoException()
            reply.writeInt(42)
            return true
        }
    }

    @Test
    fun unbindRevokesBusinessAndForwardingCallsWithoutClosingOtherConnections() {
        val bindings = PrivilegeUserServiceBindings(target, PrivilegeBinderForwarder())
        val first = bindings.bind("first")
        val second = bindings.bind("second")
        assertUsable(first)
        assertUsable(second)
        bindings.unbind("first")
        bindings.unbind("first")
        assertClosed(first)
        assertUsable(second)
        bindings.close()
        assertClosed(second)
    }

    @Test
    fun businessTransactionCodesDoNotCollideWithForwardingProtocol() {
        val business = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeNoException()
                reply.writeInt(code)
                return true
            }
        }
        val bindings = PrivilegeUserServiceBindings(business)
        val connection = connection(bindings.bind("business"))
        val forwarded = PrivilegeBinderWrapper.fromBinder(business, connection)
        try {
            for (code in listOf(PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER, PrivilegeBinderForwarder.TRANSACTION_HAS_SERVICE)) {
                assertEquals(code, read(connection.binder, code))
                assertEquals(code, read(forwarded, code))
            }
        } finally { bindings.close() }
    }

    @Test
    fun builtInServiceForwardsWithoutAnApplicationServiceBinder() {
        val bindings = PrivilegeUserServiceBindings(forwarder = PrivilegeBinderForwarder { target })
        val endpoint = bindings.bind("builtin")
        val connection = connection(endpoint)
        val wrapper = requireNotNull(PrivilegeBinderWrapper.fromSystemService(
            "test", PrivilegeSystemServiceSource.REMOTE_PROCESS, connection,
        ))
        assertEquals(42, read(wrapper))
        bindings.close()
        assertThrows(PrivilegeUnavailableException::class.java) { read(wrapper) }
        assertFalse(connection.isConnected.value)
    }

    @Test
    fun closeRevokesAllEndpointsAndPreventsFurtherBinds() {
        val bindings = PrivilegeUserServiceBindings(target, PrivilegeBinderForwarder())
        val first = bindings.bind("first")
        val second = bindings.bind("second")
        bindings.close()
        bindings.close()
        assertClosed(first)
        assertClosed(second)
        assertThrows(IllegalStateException::class.java) { bindings.bind("later") }
    }

    @Test
    fun closingDoesNotWaitForAnInFlightBusinessCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingTarget = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                reply!!.writeNoException()
                reply.writeInt(42)
                return true
            }
        }
        val bindings = PrivilegeUserServiceBindings(blockingTarget, PrivilegeBinderForwarder())
        val endpoints = bindings.bind("blocking")
        val call = FutureTask { read(connection(endpoints).binder) }
        thread { call.run() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            bindings.close()
            assertClosed(endpoints)
        } finally { release.countDown() }
        assertEquals(42, call.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun registryReturnsChildEndpointDirectlyAndRevokesCallsOnUnbindAndStop() {
        val bindings = PrivilegeUserServiceBindings(target, PrivilegeBinderForwarder())
        val childEndpoints = mutableMapOf<String, IBinder>()
        val process = object : TestUserServiceProcess() {
            override fun bind(connectionId: String): IBinder = bindings.bind(connectionId).also {
                childEndpoints[connectionId] = it
            }
            override fun unbind(connectionId: String) = bindings.unbind(connectionId)
            override fun closeBindings() = bindings.close()
        }
        val registry = registry(process)
        val spec = PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.INHERIT)
        try {
            val first = registry.bind(spec, TestBinder(), daemon = true).also { it.accept() }
            val second = requireNotNull(registry.peek(spec, TestBinder()))
            val firstEndpoints = childEndpoints.getValue(first.connectionId)
            assertSame(firstEndpoints, first.binder)
            registry.unbind(first.connectionId)
            assertClosed(firstEndpoints)
            assertUsable(childEndpoints.getValue(second.connectionId))
            registry.stop(spec)
            assertClosed(childEndpoints.getValue(second.connectionId))
        } finally { registry.destroyAll() }
    }

    @Test
    fun failedOwnerRegistrationRevokesNewChildEndpoints() {
        val bindings = PrivilegeUserServiceBindings(target, PrivilegeBinderForwarder())
        var lastEndpoints: IBinder? = null
        val process = object : TestUserServiceProcess() {
            override fun bind(connectionId: String): IBinder = bindings.bind(connectionId).also { lastEndpoints = it }
            override fun unbind(connectionId: String) = bindings.unbind(connectionId)
            override fun closeBindings() = bindings.close()
        }
        val registry = registry(process)
        val spec = PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.INHERIT)
        try {
            registry.bind(spec, TestBinder(), daemon = true).accept()
            val retained = requireNotNull(lastEndpoints)
            assertThrows(PrivilegeUnavailableException::class.java) {
                registry.bind(spec, TestBinder(alive = false))
            }
            assertClosed(requireNotNull(lastEndpoints))
            assertUsable(retained)
        } finally { registry.destroyAll() }
    }

    private fun registry(process: TestUserServiceProcess) = PrivilegeUserServiceRegistry(
        TestDedicatedUserServiceHost(process), { error("No context needed") },
    )

    // Each call uses a fresh client state so these checks exercise remote gates, not cached disconnects.
    private fun assertUsable(endpoints: IBinder) {
        assertEquals(42, read(connection(endpoints).binder))
        assertEquals(42, read(PrivilegeBinderWrapper.fromBinder(target, connection(endpoints))))
    }

    private fun assertClosed(endpoints: IBinder) {
        assertThrows(PrivilegeUnavailableException::class.java) { read(connection(endpoints).binder) }
        assertThrows(PrivilegeUnavailableException::class.java) {
            read(PrivilegeBinderWrapper.fromBinder(target, connection(endpoints)))
        }
    }

    private fun connection(endpoints: IBinder) = PrivilegeUserServiceConnection(
        binder = endpoints,
        unbindAction = {},
    )

    private fun read(binder: IBinder, code: Int = 42): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertTrue(binder.transact(code, data, reply, 0))
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
