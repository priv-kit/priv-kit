package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.internal.runtime.PrivilegeUserServiceClient
import priv.kit.core.testing.TestDedicatedUserServiceHost
import priv.kit.core.testing.TestProcess
import priv.kit.core.testing.TestUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceLifecycleTest {
    @Test
    fun stopDisconnectsAllClientsAndAllowsFreshBindingInEveryProcessMode() = runBlocking {
        for (mode in PrivilegeUserServiceProcess.entries) {
            for (daemon in listOf(false, true)) {
                val host = object : TestDedicatedUserServiceHost() {
                    override fun startDedicatedProcess(spec: PrivilegeUserServiceSpec, token: String): Process {
                        process = TestUserServiceProcess()
                        return TestProcess()
                    }
                }
                val manager = manager(host)
                // Separate clients also exercise notifications to different owner Binder tokens.
                val client = PrivilegeUserServiceClient { manager.asBinder() }
                val otherClient = PrivilegeUserServiceClient { manager.asBinder() }
                val spec = PrivilegeUserServiceSpec(tag = "stop", process = mode)
                try {
                    val first = client.bind(spec, daemon)
                    val second = otherClient.bind(spec, daemon)
                    val peeked = requireNotNull(otherClient.peek(spec))
                    assertTrue(first.isConnected.value)
                    assertTrue(second.isConnected.value)
                    assertTrue(peeked.isConnected.value)

                    client.stop(spec)
                    // Rebind immediately, without waiting for the old notifications.
                    val fresh = client.bind(spec)
                    for (old in listOf(first, second, peeked)) {
                        awaitDisconnected(old)
                        assertClosed(old.binder)
                        assertThrows(PrivilegeUnavailableException::class.java) { old.requireForwardingBinder() }
                        old.unbind()
                        old.unbind()
                    }
                    assertTrue(fresh.isConnected.value)
                    assertTrue(manager.asBinder().isBinderAlive)
                    fresh.unbind()
                    assertFalse(fresh.isConnected.value)
                    assertNull(client.peek(spec))
                    client.stop(spec)
                    client.stop(spec)
                } finally {
                    manager.destroyAll()
                }
            }
        }
    }

    @Test
    fun unbindDisconnectsOnlyItsOwnConnection() = runBlocking {
        val manager = manager(TestDedicatedUserServiceHost())
        val client = PrivilegeUserServiceClient { manager.asBinder() }
        val spec = PrivilegeUserServiceSpec()
        try {
            val first = client.bind(spec)
            val second = requireNotNull(client.peek(spec))
            first.unbind()
            first.unbind()
            assertFalse(first.isConnected.value)
            assertTrue(second.isConnected.value)
            second.unbind()
            assertFalse(second.isConnected.value)
            assertNull(client.peek(spec))
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun dedicatedProcessDeathDisconnectsExistingConnections() = runBlocking {
        val process = TestUserServiceProcess()
        val manager = manager(TestDedicatedUserServiceHost(process))
        val client = PrivilegeUserServiceClient { manager.asBinder() }
        val spec = PrivilegeUserServiceSpec(process = PrivilegeUserServiceProcess.INHERIT)
        try {
            val first = client.bind(spec, daemon = true)
            val second = requireNotNull(client.peek(spec))
            process.killBinder()
            awaitDisconnected(first)
            awaitDisconnected(second)
            assertClosed(first.binder)
            assertNull(client.peek(spec))
            first.unbind()
            second.unbind()
        } finally {
            manager.destroyAll()
        }
    }

    @Test
    fun replacingProcessDisconnectsOldInstanceAndDestroyAllDisconnectsNewInstance() = runBlocking {
        val manager = manager(TestDedicatedUserServiceHost())
        val client = PrivilegeUserServiceClient { manager.asBinder() }
        val spec = PrivilegeUserServiceSpec()
        try {
            val old = client.bind(spec, daemon = true)
            val current = client.bind(spec.copy(process = PrivilegeUserServiceProcess.INHERIT))
            awaitDisconnected(old)
            assertTrue(current.isConnected.value)
            old.unbind()
            manager.destroyAll()
            awaitDisconnected(current)
            current.unbind()
        } finally {
            manager.destroyAll()
        }
    }

    private suspend fun awaitDisconnected(connection: PrivilegeUserServiceConnection) {
        withTimeout(5_000) { connection.isConnected.first { !it } }
    }

    private fun assertClosed(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertThrows(PrivilegeUnavailableException::class.java) {
                binder.transact(42, data, reply, 0)
                reply.readException()
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun manager(host: PrivilegeUserServiceHost) = PrivilegeUserServiceManagerBinder(
        PrivilegeUserServiceRegistry(host, { error("Context is not used by this test") }),
    )
}
