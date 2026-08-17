package priv.kit.core.binder

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.ResultReceiver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.testHandshakeResult
import java.io.Closeable
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeBinderWrapperTest {
    @After
    fun clearServer() {
        runCatching { Privilege.shutdownServer() }
        ShadowServiceManager.reset()
    }

    @Test
    fun currentProcessSystemServiceLookupUsesProcessCache() {
        val serviceName = "priv.kit.test.wrapper-cache"
        val service = object : IInterface {
            override fun asBinder(): IBinder = Binder()
        }
        ShadowServiceManager.addBinderService(
            serviceName,
            IInterface::class.java,
            service,
        )

        assertNotNull(PrivilegeBinderWrapper.fromSystemService(serviceName))
        ShadowServiceManager.setServiceAvailability(serviceName, false)

        assertTrue(PrivilegeBinderWrapper.hasSystemService(serviceName))
        assertNotNull(PrivilegeBinderWrapper.fromSystemService(serviceName))
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

            assertThrows(PrivilegeServerUnavailableException::class.java) {
                wrapper.linkToDeath(recipient, 0)
            }
        }
    }

    @Test
    fun targetBinderDumpMethodsUsePrivilegeServer() {
        val targetBinder = FakeBinder()
        val observedTargetFlags = mutableListOf<Int>()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            withServer(
                FakePrivilegeServer(
                    transactHandler = { code, data, reply, flags ->
                        assertEquals(PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER, code)
                        assertEquals(0, flags)
                        data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
                        assertEquals(PrivilegeBinderWrapper.TARGET_BINDER, data.readInt())
                        assertSame(targetBinder, data.readStrongBinder())
                        assertEquals(IBinder.DUMP_TRANSACTION, data.readInt())
                        observedTargetFlags += data.readInt()
                        assertNextFileDescriptor(data)
                        assertArrayEquals(arrayOf("arg-one", "arg-two"), data.createStringArray())
                        reply!!.writeNoException()
                        true
                    },
                ),
            ) {
                val wrapper = PrivilegeBinderWrapper.fromBinder(targetBinder)

                wrapper.dump(pipe[1].fileDescriptor, arrayOf("arg-one", "arg-two"))
                wrapper.dumpAsync(pipe[1].fileDescriptor, arrayOf("arg-one", "arg-two"))
            }
        } finally {
            pipe.forEach(ParcelFileDescriptor::close)
        }

        assertEquals(listOf(0, IBinder.FLAG_ONEWAY), observedTargetFlags)
        assertEquals(0, targetBinder.directDumpCallCount)
        assertEquals(0, targetBinder.directDumpAsyncCallCount)
    }

    @Test
    fun serverProcessSystemServiceDumpForwardsSynchronousDumpTransaction() {
        val serviceName = "priv.kit.test.dump"
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            withServer(
                FakePrivilegeServer(
                    hasSystemService = true,
                    transactHandler = { code, data, reply, flags ->
                        assertEquals(PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER, code)
                        assertEquals(0, flags)
                        data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
                        assertEquals(PrivilegeBinderWrapper.TARGET_SYSTEM_SERVICE, data.readInt())
                        assertEquals(serviceName, data.readString())
                        assertEquals(IBinder.DUMP_TRANSACTION, data.readInt())
                        assertEquals(0, data.readInt())
                        assertNextFileDescriptor(data)
                        assertArrayEquals(arrayOf("--checkin"), data.createStringArray())
                        reply!!.writeNoException()
                        true
                    },
                ),
            ) {
                val wrapper = PrivilegeBinderWrapper.fromSystemService(
                    serviceName = serviceName,
                    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                )!!

                wrapper.dump(pipe[1].fileDescriptor, arrayOf("--checkin"))
            }
        } finally {
            pipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun serverProcessSystemServiceDumpAsyncForwardsOneWayDumpTransaction() {
        val serviceName = "priv.kit.test.dump-async"
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            withServer(
                FakePrivilegeServer(
                    hasSystemService = true,
                    transactHandler = { code, data, reply, flags ->
                        assertEquals(PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER, code)
                        assertEquals(0, flags)
                        data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
                        assertEquals(PrivilegeBinderWrapper.TARGET_SYSTEM_SERVICE, data.readInt())
                        assertEquals(serviceName, data.readString())
                        assertEquals(IBinder.DUMP_TRANSACTION, data.readInt())
                        assertEquals(IBinder.FLAG_ONEWAY, data.readInt())
                        assertNextFileDescriptor(data)
                        assertNull(data.createStringArray())
                        reply!!.writeException(IllegalStateException("one-way reply is ignored"))
                        true
                    },
                ),
            ) {
                val wrapper = PrivilegeBinderWrapper.fromSystemService(
                    serviceName = serviceName,
                    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                )!!

                wrapper.dumpAsync(pipe[1].fileDescriptor, null)
            }
        } finally {
            pipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun serverProcessSystemServiceShellCommandForwardsFrameworkTransaction() {
        val serviceName = "priv.kit.test.shell-command"
        val inputPipe = ParcelFileDescriptor.createPipe()
        val outputPipe = ParcelFileDescriptor.createPipe()
        val errorPipe = ParcelFileDescriptor.createPipe()
        val resultReceiver = ResultReceiver(null)
        try {
            withServer(
                FakePrivilegeServer(
                    hasSystemService = true,
                    transactHandler = { code, data, reply, flags ->
                        assertEquals(PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER, code)
                        assertEquals(0, flags)
                        data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
                        assertEquals(PrivilegeBinderWrapper.TARGET_SYSTEM_SERVICE, data.readInt())
                        assertEquals(serviceName, data.readString())
                        assertEquals(SHELL_COMMAND_TRANSACTION, data.readInt())
                        assertEquals(0, data.readInt())
                        assertNextFileDescriptor(data)
                        assertNextFileDescriptor(data)
                        assertNextFileDescriptor(data)
                        assertArrayEquals(arrayOf("reset", "--user", "10"), data.createStringArray())
                        assertNull(data.readStrongBinder())
                        assertNotNull(data.readStrongBinder())
                        reply!!.writeNoException()
                        true
                    },
                ),
            ) {
                val wrapper = PrivilegeBinderWrapper.fromSystemService(
                    serviceName = serviceName,
                    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                )!!

                wrapper.shellCommand(
                    input = inputPipe[0].fileDescriptor,
                    output = outputPipe[1].fileDescriptor,
                    error = errorPipe[1].fileDescriptor,
                    args = arrayOf("reset", "--user", "10"),
                    shellCallback = null,
                    resultReceiver = resultReceiver,
                )
            }
        } finally {
            inputPipe.forEach(ParcelFileDescriptor::close)
            outputPipe.forEach(ParcelFileDescriptor::close)
            errorPipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun rawTransactPropagatesRemoteException() {
        val remoteException = RemoteException("target exploded")
        withServer(FakePrivilegeServer(transactException = remoteException)) {
            val wrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder())
            val data = Parcel.obtain()

            val thrown = try {
                assertThrows(RemoteException::class.java) {
                    wrapper.transact(1, data, null, 0)
                }
            } finally {
                data.recycle()
            }

            assertEquals(remoteException, thrown)
        }
    }

    @Test
    fun rawTransactPropagatesTargetDeadObjectWhileServerIsAlive() {
        val deadObjectException = DeadObjectException("target died")
        withServer(FakePrivilegeServer(transactException = deadObjectException)) {
            val wrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder())
            val data = Parcel.obtain()

            val thrown = try {
                assertThrows(DeadObjectException::class.java) {
                    wrapper.transact(1, data, null, 0)
                }
            } finally {
                data.recycle()
            }

            assertSame(deadObjectException, thrown)
        }
    }

    @Test
    fun rawTransactServerDeathUsesBinderDiedFallbackWithoutGuessingOrigin() {
        val deadObjectException = DeadObjectException("server died")
        withServer(FakePrivilegeServer(transactException = deadObjectException)) { server ->
            val wrapper = PrivilegeBinderWrapper.fromBinder(FakeBinder())
            val data = Parcel.obtain()
            server.binder.kill()
            var observedFailure: PrivilegeBinderCallFailure? = null

            val result = try {
                PrivilegeBinderCall.orElse(
                    fallback = { failure ->
                        observedFailure = failure
                        false
                    },
                ) {
                    wrapper.transact(1, data, null, 0)
                }
            } finally {
                data.recycle()
            }

            assertFalse(result)
            val failure = observedFailure as PrivilegeBinderCallFailure.BinderDied
            assertSame(deadObjectException, failure.exception)
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

    @Test
    fun serverStateTracksDirectConnection() {
        PrivilegeContext.install(RuntimeEnvironment.getApplication())
        val server = FakePrivilegeServer()
        val serverInfo = PrivilegeServerInfo(
            uid = 2000,
            pid = 1234,
            protocolVersion = PrivilegeProtocol.VERSION,
            lifecycleBinder = android.os.Binder(),
        )
        try {
            repeat(2) {
                Privilege.connectHandshake(
                    handshakeResult = testHandshakeResult(
                        serverInfo = serverInfo,
                        serverBinder = server.asBinder(),
                    ),
                    startupLogListener = null,
                )
            }
            assertSame(serverInfo, Privilege.serverState.value)
        } finally {
            runCatching { Privilege.shutdownServer() }
            resetRuntimeConnectionListener()
        }
    }

    private fun withServer(
        server: FakePrivilegeServer,
        block: (FakePrivilegeServer) -> Unit,
    ) {
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        try {
            block(server)
        } finally {
            runCatching { Privilege.shutdownServer() }
        }
    }

    private fun resetRuntimeConnectionListener() {
        val field = Privilege::class.java.getDeclaredField("runtimeConnectionListener")
            .apply { isAccessible = true }
        (field.get(Privilege) as? Closeable)?.close()
        field.set(Privilege, null)
    }

    private fun assertNextFileDescriptor(data: Parcel) {
        val descriptor = data.readFileDescriptor()
        try {
            assertNotNull(descriptor)
        } finally {
            descriptor?.close()
        }
    }

    private class FakePrivilegeServer(
        private val hasSystemService: Boolean = false,
        private val transactException: RemoteException? = null,
        transactHandler: ((Int, Parcel, Parcel?, Int) -> Boolean)? = null,
    ) : IPrivilegeServer {
        val binder = FakeBinder(
            localInterface = this,
            transactException = transactException,
            transactHandler = transactHandler,
        )
        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun hasSystemService(serviceName: String): Boolean = hasSystemService

        override fun checkServerPermission(permission: String): Int =
            android.content.pm.PackageManager.PERMISSION_DENIED

        override fun checkPermission(
            permName: String,
            pkgName: String,
            userId: Int,
        ): Int = android.content.pm.PackageManager.PERMISSION_DENIED

        override fun grantRuntimePermission(
            packageName: String,
            permissionName: String,
            userId: Int,
        ) = Unit

        override fun revokeRuntimePermission(
            packageName: String,
            permissionName: String,
            userId: Int,
        ) = Unit
    }

    private class FakeBinder(
        localInterface: android.os.IInterface? = null,
        private val transactException: RemoteException? = null,
        private val transactHandler: ((Int, Parcel, Parcel?, Int) -> Boolean)? = null,
        alive: Boolean = true,
    ) : TestBinder(localInterface = localInterface, alive = alive) {
        var directDumpCallCount: Int = 0
            private set
        var directDumpAsyncCallCount: Int = 0
            private set

        fun kill() {
            killBinder(notifyDeathRecipients = false)
        }

        override fun dump(fd: FileDescriptor, args: Array<out String>?) {
            directDumpCallCount += 1
        }

        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
            directDumpAsyncCallCount += 1
        }

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            transactException?.let { throw it }
            transactHandler?.let { handler ->
                data.setDataPosition(0)
                return try {
                    handler(code, data, reply, flags)
                } finally {
                    reply?.setDataPosition(0)
                }
            }
            return super.transact(code, data, reply, flags)
        }
    }

    private companion object {
        const val SHELL_COMMAND_TRANSACTION: Int = 0x5f434d44
    }
}
