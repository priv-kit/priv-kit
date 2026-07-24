package priv.kit.core

import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.binder.PrivilegeBinderCall
import priv.kit.core.binder.PrivilegeBinderCallFailure
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.binder.PrivilegeServerUnavailableException
import priv.kit.core.internal.core.PrivilegeAndroidUsers
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.testing.TestBinder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PrivilegeTest {
    @After
    fun clearServer() {
        runCatching { Privilege.shutdownServer() }
    }

    @Test
    fun userServiceConnectionCloseIsIdempotent() {
        val unbindCalls = AtomicInteger(0)
        val connection = PrivilegeUserServiceConnection(
            id = "connection-id",
            binder = TestBinder(),
            unbind = { unbindCalls.incrementAndGet() },
        )

        connection.close()
        connection.close()

        assertEquals(1, unbindCalls.get())
    }

    @Test
    fun getServerInfoWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getServerInfo()
        }
    }

    @Test
    fun classpathIdentityIncludesPathSizeAndModifiedSeconds() {
        val directory = File("build/tmp/classpathIdentityTest").also { it.mkdirs() }
        val apk = File(directory, "base.apk").also {
            it.writeText("apk")
        }

        val identity = PrivilegeHandshakeContract.classpathIdentity(apk.path)

        assertEquals(
            "${apk.path}@${apk.length()}@${apk.lastModified() / 1000L}",
            identity,
        )
    }

    @Test
    fun userIdIsDerivedFromAndroidUidRange() {
        assertEquals(0, PrivilegeAndroidUsers.userIdFromUid(10_123))
        assertEquals(10, PrivilegeAndroidUsers.userIdFromUid(1_012_345))
    }

    @Test
    fun rootFallbackStopsWhenDetachedServerMayExist() {
        assertTrue(rootServerLaunchMayHaveCompleted(processIsAlive = true, exitCode = null))
        assertTrue(rootServerLaunchMayHaveCompleted(processIsAlive = false, exitCode = null))
        assertTrue(rootServerLaunchMayHaveCompleted(processIsAlive = false, exitCode = 0))
        assertFalse(rootServerLaunchMayHaveCompleted(processIsAlive = false, exitCode = 1))
    }

    @Test
    fun getServerInfoDoesNotPingServerUntilExplicitPing() {
        val server = FakePrivilegeServer()
        val serverInfo = PrivilegeServerInfo(
            uid = 2000,
            pid = 1234,
            protocolVersion = PrivilegeProtocol.VERSION,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = serverInfo,
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        server.killBinder()

        assertEquals(serverInfo, Privilege.getServerInfo())
        assertFalse(Privilege.pingServer())
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getServerInfo()
        }
    }

    @Test
    fun checkServerPermissionReturnsServerResult() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            Privilege.checkServerPermission("android.permission.GRANT_RUNTIME_PERMISSIONS"),
        )
    }

    @Test
    fun deadServerCallUsesFallbackAndClearsConnection() {
        val deadObjectException = DeadObjectException("server died")
        val server = FakePrivilegeServer(
            checkServerPermissionCall = { throw deadObjectException },
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        var observedFailure: PrivilegeBinderCallFailure? = null

        val result = PrivilegeBinderCall.orElse(
            fallback = { failure ->
                observedFailure = failure
                PackageManager.PERMISSION_DENIED
            },
        ) {
            Privilege.checkServerPermission("android.permission.GRANT_RUNTIME_PERMISSIONS")
        }

        assertEquals(PackageManager.PERMISSION_DENIED, result)
        val failure = observedFailure as PrivilegeBinderCallFailure.ServerUnavailable
        assertSame(deadObjectException, failure.exception.cause)
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getServerInfo()
        }
    }

    @Test
    fun remoteExceptionFromConfirmedDeadServerUsesFallback() {
        val remoteException = RemoteException("server transport failed")
        val server = FakePrivilegeServer(
            checkServerPermissionCall = { throw remoteException },
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        server.killBinder()
        var observedFailure: PrivilegeBinderCallFailure? = null

        val result = PrivilegeBinderCall.orElse(
            fallback = { failure ->
                observedFailure = failure
                PackageManager.PERMISSION_DENIED
            },
        ) {
            Privilege.checkServerPermission("android.permission.GRANT_RUNTIME_PERMISSIONS")
        }

        assertEquals(PackageManager.PERMISSION_DENIED, result)
        val failure = observedFailure as PrivilegeBinderCallFailure.ServerUnavailable
        assertSame(remoteException, failure.exception.cause)
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getServerInfo()
        }
    }

    @Test
    fun staleServerFailureDoesNotDisconnectReplacementServer() {
        val callEntered = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val oldServer = FakePrivilegeServer(
            checkServerPermissionCall = {
                callEntered.countDown()
                check(releaseCall.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the old server call"
                }
                throw DeadObjectException("old server died")
            },
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = oldServer.asBinder(),
            ),
            startupLogListener = null,
        )
        val result = AtomicReference<Int?>()
        val failure = AtomicReference<PrivilegeBinderCallFailure?>()
        val unexpected = AtomicReference<Throwable?>()
        val worker = thread(name = "stale-server-call") {
            try {
                result.set(
                    PrivilegeBinderCall.orElse(
                        fallback = {
                            failure.set(it)
                            PackageManager.PERMISSION_DENIED
                        },
                    ) {
                        Privilege.checkServerPermission(
                            "android.permission.GRANT_RUNTIME_PERMISSIONS",
                        )
                    },
                )
            } catch (throwable: Throwable) {
                unexpected.set(throwable)
            }
        }
        val replacementInfo = PrivilegeServerInfo(
            uid = 2000,
            pid = 5678,
            protocolVersion = PrivilegeProtocol.VERSION,
        )
        try {
            assertTrue(callEntered.await(5, TimeUnit.SECONDS))
            Privilege.connectHandshake(
                handshakeResult = PrivilegeServerHandshakeResult(
                    serverInfo = replacementInfo,
                    serverBinder = FakePrivilegeServer().asBinder(),
                ),
                startupLogListener = null,
            )
        } finally {
            releaseCall.countDown()
            worker.join(5_000)
        }

        assertFalse(worker.isAlive)
        assertNull(unexpected.get())
        assertEquals(PackageManager.PERMISSION_DENIED, result.get())
        assertTrue(failure.get() is PrivilegeBinderCallFailure.ServerUnavailable)
        assertEquals(replacementInfo, Privilege.getServerInfo())
    }

    @Test
    fun rootServerIsNeverPermissionRestrictedWithoutPermissionCheck() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 0,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertFalse(Privilege.isPermissionRestricted())
        assertTrue(server.serverPermissionChecks.isEmpty())
    }

    @Test
    fun nonRootPermissionRestrictionUsesGrantPermissionRegardlessOfUid() {
        val grantedServer = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 1000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = grantedServer.asBinder(),
            ),
            startupLogListener = null,
        )

        assertFalse(Privilege.isPermissionRestricted())
        assertEquals(
            listOf("android.permission.GRANT_RUNTIME_PERMISSIONS"),
            grantedServer.serverPermissionChecks,
        )

        Privilege.shutdownServer()
        val deniedServer = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 5678,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = deniedServer.asBinder(),
            ),
            startupLogListener = null,
        )

        assertTrue(Privilege.isPermissionRestricted())
        assertEquals(
            listOf("android.permission.GRANT_RUNTIME_PERMISSIONS"),
            deniedServer.serverPermissionChecks,
        )
    }

    @Test
    fun permissionRestrictionWithoutConnectionThrowsDisconnectedException() {
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.isPermissionRestricted()
        }
    }

    @Test
    fun checkPermissionReturnsPackageManagerResult() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            Privilege.checkPermission(
                permName = "android.permission.WRITE_SECURE_SETTINGS",
                pkgName = "test.package",
                userId = 10,
            ),
        )
        assertEquals(
            listOf(
                PackagePermissionCheck(
                    permName = "android.permission.WRITE_SECURE_SETTINGS",
                    pkgName = "test.package",
                    userId = 10,
                ),
            ),
            server.packagePermissionChecks,
        )
    }

    @Test
    fun grantRuntimePermissionPassesThroughWithoutServerGrantCheck() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )
        Privilege.connectHandshake(
            handshakeResult = PrivilegeServerHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        Privilege.grantRuntimePermission(
            packageName = "test.package",
            permissionName = "android.permission.WRITE_SECURE_SETTINGS",
            userId = 10,
        )

        assertEquals(
            listOf(
                RuntimePermissionGrant(
                    packageName = "test.package",
                    permissionName = "android.permission.WRITE_SECURE_SETTINGS",
                    userId = 10,
                ),
            ),
            server.runtimePermissionGrants,
        )
        assertTrue(server.serverPermissionChecks.isEmpty())
    }

    @Test
    fun runtimeGrantRequiresGrantPermissionForNonRootServer() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )

        assertFalse(
            Privilege.grantRuntimePermissionForRuntime(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                server = server,
                packageName = "test.package",
                permissionName = "android.permission.WRITE_SECURE_SETTINGS",
                userId = 10,
            ),
        )

        assertEquals(
            listOf("android.permission.GRANT_RUNTIME_PERMISSIONS"),
            server.serverPermissionChecks,
        )
        assertTrue(server.runtimePermissionGrants.isEmpty())
    }

    @Test
    fun runtimeGrantSkipsGrantPermissionCheckForRootServer() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )

        assertTrue(
            Privilege.grantRuntimePermissionForRuntime(
                serverInfo = PrivilegeServerInfo(
                    uid = 0,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                server = server,
                packageName = "test.package",
                permissionName = "android.permission.WRITE_SECURE_SETTINGS",
                userId = 10,
            ),
        )

        assertTrue(server.serverPermissionChecks.isEmpty())
        assertEquals(
            listOf(
                RuntimePermissionGrant(
                    packageName = "test.package",
                    permissionName = "android.permission.WRITE_SECURE_SETTINGS",
                    userId = 10,
                ),
            ),
            server.runtimePermissionGrants,
        )
    }

    private class FakePrivilegeServer(
        private val permissionResult: Int = PackageManager.PERMISSION_DENIED,
        private val checkServerPermissionCall: ((String) -> Int)? = null,
    ) : IPrivilegeServer {
        private val binder = TestBinder(localInterface = this)
        val serverPermissionChecks = mutableListOf<String>()
        val packagePermissionChecks = mutableListOf<PackagePermissionCheck>()
        val runtimePermissionGrants = mutableListOf<RuntimePermissionGrant>()

        fun killBinder() {
            binder.killBinder(notifyDeathRecipients = false)
        }

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun hasSystemService(serviceName: String): Boolean = false

        override fun checkServerPermission(permission: String): Int {
            serverPermissionChecks += permission
            return checkServerPermissionCall?.invoke(permission) ?: permissionResult
        }

        override fun checkPermission(
            permName: String,
            pkgName: String,
            userId: Int,
        ): Int {
            packagePermissionChecks += PackagePermissionCheck(
                permName = permName,
                pkgName = pkgName,
                userId = userId,
            )
            return permissionResult
        }

        override fun grantRuntimePermission(
            packageName: String,
            permissionName: String,
            userId: Int,
        ) {
            runtimePermissionGrants += RuntimePermissionGrant(
                packageName = packageName,
                permissionName = permissionName,
                userId = userId,
            )
        }
    }

    private data class PackagePermissionCheck(
        val permName: String,
        val pkgName: String,
        val userId: Int,
    )

    private data class RuntimePermissionGrant(
        val packageName: String,
        val permissionName: String,
        val userId: Int,
    )

}
