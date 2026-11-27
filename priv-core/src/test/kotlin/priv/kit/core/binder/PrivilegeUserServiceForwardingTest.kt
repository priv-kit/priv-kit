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
import priv.kit.core.internal.userservice.PrivilegeUserServiceGateBinder
import priv.kit.core.internal.userservice.PrivilegeUserServiceRegistry
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.TestEmbeddedUserServiceHost
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeUserServiceForwardingTest {
    @Test
    fun forwardingWrappersObserveEndpointDeathAndUnlinkAfterUnbind(): Unit = runBlocking {
        val target = TestBinder()
        val gate = PrivilegeUserServiceGateBinder(forwarder = PrivilegeBinderForwarder { target })
        val endpoint = object : TestBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                gate.transact(code, data, reply, flags)
        }
        val connection = PrivilegeUserServiceConnection(binder = endpoint, unbindAction = {})
        val wrappers = listOf(
            PrivilegeBinderWrapper.fromBinder(target, connection),
            requireNotNull(PrivilegeBinderWrapper.fromSystemService(
                "test.service", PrivilegeSystemServiceSource.REMOTE_PROCESS, connection,
            )),
        )
        var deaths = 0
        val recipients = wrappers.map { wrapper ->
            IBinder.DeathRecipient { deaths += 1 }.also { wrapper.linkToDeath(it, 0) }
        }
        assertEquals(2, endpoint.deathRecipientCount)
        assertEquals(0, target.deathRecipientCount)

        target.killBinder(notifyDeathRecipients = true)
        assertEquals(0, deaths)
        connection.unbind()
        assertEquals(0, deaths)
        wrappers.forEach { wrapper ->
            assertFalse(wrapper.isBinderAlive)
            assertThrows(PrivilegeUnavailableException::class.java) {
                wrapper.linkToDeath(IBinder.DeathRecipient {}, 0)
            }
        }
        assertTrue(wrappers[0].unlinkToDeath(recipients[0], 0))
        endpoint.killBinder(notifyDeathRecipients = true)
        assertEquals(1, deaths)
    }

    @Test
    fun forwardingLivenessUsesEndpointWhileTargetRemainsAlive() {
        val target = TestBinder()
        val endpoint = TestBinder()
        val connection = PrivilegeUserServiceConnection(binder = endpoint, unbindAction = {})
        val wrapper = PrivilegeBinderWrapper.fromBinder(target, connection)
        assertTrue(wrapper.isBinderAlive)
        assertTrue(wrapper.pingBinder())

        endpoint.killBinder(notifyDeathRecipients = true)

        assertTrue(target.isBinderAlive)
        assertFalse(connection.binder.isBinderAlive)
        assertFalse(wrapper.isBinderAlive)
        assertFalse(wrapper.pingBinder())
    }

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
        val serviceName = "priv.kit.test.user-service-only"
        val forwarder = PrivilegeBinderForwarder { name -> if (name == serviceName) target else null }
        val connection = connection(forwarder)
        val remote = PrivilegeSystemServiceSource.REMOTE_PROCESS
        assertFalse(PrivilegeBinderWrapper.hasSystemService(serviceName, userService = connection))
        assertNull(PrivilegeBinderWrapper.fromSystemService(serviceName, userService = connection))
        assertTrue(PrivilegeBinderWrapper.hasSystemService(serviceName, source = remote, userService = connection))
        val wrapper = PrivilegeBinderWrapper.fromSystemService(serviceName, source = remote, userService = connection)!!
        assertNull(PrivilegeBinderWrapper.fromSystemService("missing", source = remote, userService = connection))
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
            assertThrows(PrivilegeUnavailableException::class.java) { wrapper.transact(42, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
            pipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun targetDeathDoesNotDisconnectHealthyForwardingChannel() {
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                throw DeadObjectException("target died")
            }
        }
        val connection = connection(PrivilegeBinderForwarder())
        val wrapper = PrivilegeBinderWrapper.fromBinder(target, connection)
        val data = Parcel.obtain()
        try {
            PrivilegeBinderCall.orElse(
                fallback = { assertTrue(it is PrivilegeBinderCallFailure.BinderDied) },
            ) {
                wrapper.transact(42, data, null, 0)
                fail("Dead target should fail")
            }
            assertTrue(connection.isConnected.value)
        } finally { data.recycle() }
    }

    @Test
    fun closedGateDisconnectsClientBeforeNotificationArrives() {
        val gate = PrivilegeUserServiceGateBinder(PrivilegeBinderForwarder())
        val connection = connection(gate)
        val wrapper = PrivilegeBinderWrapper.fromBinder(Binder(), connection)
        val data = Parcel.obtain()
        try {
            gate.close()
            assertTrue(connection.isConnected.value)
            assertThrows(PrivilegeUnavailableException::class.java) { wrapper.transact(42, data, null, 0) }
            assertFalse(connection.isConnected.value)
        } finally { data.recycle() }
    }

    @Test
    fun businessErrorsInTargetRepliesAreNotClassifiedAsChannelFailures() {
        for (exception in listOf(IllegalStateException("business state"), SecurityException("permission denied"))) {
            val target = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    reply!!.writeException(exception)
                    return true
                }
            }
            val connection = connection(PrivilegeBinderForwarder(), service = target)
            val forwarded = PrivilegeBinderWrapper.fromBinder(target, connection)
            for (binder in listOf(connection.binder, forwarded)) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    val thrown = assertThrows(exception.javaClass) {
                        PrivilegeBinderCall.orElse(
                            fallback = { throw AssertionError("Business error must not invoke fallback") },
                        ) {
                            binder.transact(42, data, reply, 0)
                            reply.readException()
                        }
                    }
                    assertEquals(exception.message, thrown.message)
                    assertTrue(connection.isConnected.value)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }

    @Test
    fun directServiceCallsAfterUnbindFailAsUnavailable(): Unit = runBlocking {
        val connection = connection(PrivilegeBinderForwarder())
        connection.unbind()
        val data = Parcel.obtain()
        try {
            assertThrows(PrivilegeUnavailableException::class.java) { connection.binder.transact(42, data, null, 0) }
        } finally { data.recycle() }
    }

    @Test
    fun businessDependencyDeathDoesNotDisconnectUserService() {
        val dependency = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                throw DeadObjectException("business dependency died")
            }
        }
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 42) return dependency.transact(code, data, reply, flags)
                reply!!.writeNoException()
                reply.writeString("still running")
                return true
            }
        }
        val connection = connection(PrivilegeBinderForwarder(), service = service)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val failure = PrivilegeBinderCall.orElse<PrivilegeBinderCallFailure>(fallback = { it }) {
                connection.binder.transact(42, data, reply, 0)
                error("The dependency should fail")
            }
            assertTrue(failure is PrivilegeBinderCallFailure.BinderDied)
            assertEquals("business dependency died", failure.exception.message)
            assertTrue(connection.isConnected.value)
            assertTrue(connection.binder.transact(43, data, reply, 0))
            reply.readException()
            assertEquals("still running", reply.readString())
            val forwarded = PrivilegeBinderWrapper.fromBinder(service, connection)
            assertTrue(forwarded.transact(43, data, reply, 0))
            reply.readException()
            assertEquals("still running", reply.readString())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun remoteLookupThroughClosedGateDisconnectsClient() {
        val gate = PrivilegeUserServiceGateBinder()
        val connection = connection(gate)
        gate.close()
        assertThrows(PrivilegeUnavailableException::class.java) {
            PrivilegeBinderWrapper.hasSystemService(
                "test", PrivilegeSystemServiceSource.REMOTE_PROCESS, connection,
            )
        }
        assertFalse(connection.isConnected.value)
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
        val spec = PrivilegeUserServiceSpec()
        val first = registry.bind(spec, TestBinder())
        val second = registry.bind(spec, TestBinder())
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        }
        val firstWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(first.binder))
        val secondWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(second.binder))
        val data = Parcel.obtain()
        try {
            assertTrue(firstWrapper.transact(42, data, null, 0))
            registry.unbind(first.connectionId)
            assertConnectionClosed(firstWrapper, data)
            assertTrue(secondWrapper.transact(42, data, null, 0))
            registry.destroyAll()
            assertConnectionClosed(secondWrapper, data)
        } finally { data.recycle() }
    }

    @Test
    fun clientDeathClosesOldForwarderWhileDaemonCanBeReconnected() {
        val registry = PrivilegeUserServiceRegistry(
            TestEmbeddedUserServiceHost(),
            embeddedContextRuntimeProvider = { error("No context needed") },
        )
        val spec = PrivilegeUserServiceSpec()
        val client = TestBinder()
        val original = registry.bind(spec, client, daemon = true).also { it.accept() }
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        }
        val oldWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(original.binder))
        val data = Parcel.obtain()
        try {
            assertTrue(oldWrapper.transact(42, data, null, 0))
            client.killBinder(notifyDeathRecipients = true)
            registry.destroyOnOwnerDeath()
            assertConnectionClosed(oldWrapper, data)

            val reconnected = requireNotNull(registry.peek(spec, TestBinder()))
            val newWrapper = PrivilegeBinderWrapper.fromBinder(target, connection(reconnected.binder))
            assertTrue(newWrapper.transact(42, data, null, 0))
            registry.unbind(reconnected.connectionId)
            registry.stop(spec)
            assertNull(registry.peek(spec, TestBinder()))
        } finally {
            data.recycle()
            registry.destroyAll()
        }
    }

    @Test
    fun processConfigurationRoundTripsAllModes() {
        val expectedUids = mapOf(
            PrivilegeUserServiceProcess.INHERIT to -2,
            PrivilegeUserServiceProcess.EMBEDDED to -1,
            PrivilegeUserServiceProcess.ROOT to 0,
            PrivilegeUserServiceProcess.SYSTEM to 1000,
            PrivilegeUserServiceProcess.SHELL to 2000,
        )
        for ((process, uid) in expectedUids) {
            val spec = PrivilegeUserServiceSpec(process = process)
            val request = PrivilegeUserServiceContract.requestBundle(spec)
            assertTrue(request.containsKey(PrivilegeUserServiceContract.KEY_PROCESS_UID))
            assertEquals(uid, request.getInt(PrivilegeUserServiceContract.KEY_PROCESS_UID))
            assertEquals(spec, PrivilegeUserServiceContract.specFrom(request))
        }
        val missingUidRequest = PrivilegeUserServiceContract.requestBundle(PrivilegeUserServiceSpec()).apply {
            remove(PrivilegeUserServiceContract.KEY_PROCESS_UID)
        }
        assertEquals(PrivilegeUserServiceProcess.EMBEDDED, PrivilegeUserServiceContract.specFrom(missingUidRequest).process)
        for (uid in listOf(-3, 10001, Int.MAX_VALUE)) {
            val request = PrivilegeUserServiceContract.requestBundle(PrivilegeUserServiceSpec()).apply {
                putInt(PrivilegeUserServiceContract.KEY_PROCESS_UID, uid)
            }
            assertThrows(IllegalStateException::class.java) { PrivilegeUserServiceContract.specFrom(request) }
        }
    }

    @Test
    fun blankTagsRemainInvalid() {
        for (tag in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { PrivilegeUserServiceSpec(tag = tag) }
            val request = PrivilegeUserServiceContract.requestBundle(PrivilegeUserServiceSpec()).apply {
                putString(PrivilegeUserServiceContract.KEY_TAG, tag)
            }
            assertThrows(IllegalStateException::class.java) { PrivilegeUserServiceContract.specFrom(request) }
        }
    }

    private fun assertConnectionClosed(binder: IBinder, data: Parcel) {
        val reply = Parcel.obtain()
        try {
            assertThrows(PrivilegeUnavailableException::class.java) {
                binder.transact(42, data, reply, 0)
                reply.readException()
            }
        } finally {
            reply.recycle()
        }
    }

    private fun connection(forwarder: IBinder, service: IBinder = Binder()) = PrivilegeUserServiceConnection(
        binder = if (forwarder is PrivilegeUserServiceGateBinder) forwarder else
            PrivilegeUserServiceGateBinder(service, forwarder as PrivilegeBinderForwarder),
        unbindAction = {},
    )
}
