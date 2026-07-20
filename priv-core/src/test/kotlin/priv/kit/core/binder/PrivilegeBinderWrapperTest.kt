package priv.kit.core.binder

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.testing.TestBinder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

            assertThrows(PrivilegeServerUnavailableException::class.java) {
                wrapper.linkToDeath(recipient, 0)
            }
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
        private val transactException: RemoteException? = null,
    ) : IPrivilegeServer {
        val binder = FakeBinder(
            localInterface = this,
            transactException = transactException,
        )

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

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
    }

    private class FakeBinder(
        localInterface: android.os.IInterface? = null,
        private val transactException: RemoteException? = null,
        alive: Boolean = true,
    ) : TestBinder(localInterface = localInterface, alive = alive) {
        fun kill() {
            killBinder(notifyDeathRecipients = false)
        }

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            transactException?.let { throw it }
            return super.transact(code, data, reply, flags)
        }
    }
}
