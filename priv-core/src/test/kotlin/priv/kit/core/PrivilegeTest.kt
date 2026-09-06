package priv.kit.core

import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.testHandshakeResult
import java.io.Closeable
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
        PrivilegeConfig.configure(
            followDeathDelayMillis = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
            activeReconnectOnOwnerDeath =
                PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
        )
        resetRuntimeConnectionListener()
    }

    private fun resetRuntimeConnectionListener() {
        val field = Privilege::class.java.getDeclaredField("runtimeConnectionListener")
            .apply { isAccessible = true }
        (field.get(Privilege) as? Closeable)?.close()
        field.set(Privilege, null)
    }

    @Test
    fun userServiceConnectionUnbindIsIdempotent() = runBlocking {
        val unbindCalls = AtomicInteger(0)
        val connection = PrivilegeUserServiceConnection(
            binder = TestBinder(),
            unbindAction = { unbindCalls.incrementAndGet() },
        )

        connection.unbind()
        connection.unbind()

        assertEquals(1, unbindCalls.get())
    }

    @Test
    fun userServiceConnectionRetriesFailedUnbind() = runBlocking {
        val unbindCalls = AtomicInteger(0)
        val connection = PrivilegeUserServiceConnection(
            binder = TestBinder(),
            unbindAction = {
                if (unbindCalls.incrementAndGet() == 1) {
                    throw IllegalStateException("unbind failed")
                }
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { connection.unbind() }
        }
        connection.unbind()
        connection.unbind()

        assertEquals(2, unbindCalls.get())
    }

    @Test
    fun getServerInfoWithoutServerThrowsDisconnectedException() {
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getServerInfo()
        }
    }

    @Test
    fun serverInfoExposesStableDedicatedLifecycleBinder() {
        val server = FakePrivilegeServer()
        val lifecycleBinder = TestBinder()
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = lifecycleBinder,
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertSame(lifecycleBinder, Privilege.getServerInfo().lifecycleBinder)
        assertSame(lifecycleBinder, Privilege.serverState.value?.lifecycleBinder)
        assertNotSame(server.asBinder(), lifecycleBinder)
    }

    @Test
    fun replacementServerReturnsDifferentLifecycleBinder() {
        val firstServer = FakePrivilegeServer()
        val firstLifecycleBinder = TestBinder()
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = firstLifecycleBinder,
                ),
                serverBinder = firstServer.asBinder(),
            ),
            startupLogListener = null,
        )
        val replacementServer = FakePrivilegeServer()
        val replacementLifecycleBinder = TestBinder()

        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 5678,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = replacementLifecycleBinder,
                ),
                serverBinder = replacementServer.asBinder(),
            ),
            startupLogListener = null,
        )

        assertSame(replacementLifecycleBinder, Privilege.getServerInfo().lifecycleBinder)
        assertNotSame(firstLifecycleBinder, Privilege.getServerInfo().lifecycleBinder)
    }

    @Test
    fun connectedServerReceivesCompleteRuntimeConfigForPropertyUpdates() {
        val server = FakePrivilegeServer()
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = TestBinder(),
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        server.runtimeConfigUpdates.clear()

        PrivilegeConfig.followDeathDelayMillis = 12_345L
        PrivilegeConfig.activeReconnectOnOwnerDeath = true

        assertEquals(
            listOf(
                RuntimeConfigUpdate(
                    followDeathDelayMillis = 12_345L,
                    activeReconnectOnOwnerDeath = false,
                ),
                RuntimeConfigUpdate(
                    followDeathDelayMillis = 12_345L,
                    activeReconnectOnOwnerDeath = true,
                ),
            ),
            server.runtimeConfigUpdates,
        )
    }

    @Test
    fun connectionSynchronizesLatestRuntimeConfig() {
        PrivilegeConfig.configure(
            followDeathDelayMillis = 54_321L,
            activeReconnectOnOwnerDeath = true,
        )
        val server = FakePrivilegeServer()

        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = TestBinder(),
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertEquals(
            RuntimeConfigUpdate(
                followDeathDelayMillis = 54_321L,
                activeReconnectOnOwnerDeath = true,
            ),
            server.runtimeConfigUpdates.last(),
        )
    }

    @Test
    fun prepareOwnerRestartForwardsPassiveReconnectTimeout() {
        val server = FakePrivilegeServer()
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = TestBinder(),
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        Privilege.prepareOwnerRestart(passiveReconnectTimeoutMillis = 7_500L)

        assertEquals(listOf(7_500L), server.ownerRestartTimeouts)
    }

    @Test
    fun prepareOwnerRestartRejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException::class.java) {
            Privilege.prepareOwnerRestart(passiveReconnectTimeoutMillis = 0L)
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
            lifecycleBinder = android.os.Binder(),
        )
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = serverInfo,
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )
        server.killBinder()

        assertSame(serverInfo, Privilege.getServerInfo())
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
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
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
            lifecycleBinder = android.os.Binder(),
        )
        try {
            assertTrue(callEntered.await(5, TimeUnit.SECONDS))
            Privilege.connectHandshake(
                handshakeResult = testHandshakeResult(
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
        assertSame(replacementInfo, Privilege.getServerInfo())
    }

    @Test
    fun connectingNewHandshakeUnlinksOldConnectionBeforeInstallingNewServer() {
        val oldServer = FakePrivilegeServer()
        val newServer = FakePrivilegeServer()
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 0,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
                ),
                serverBinder = oldServer.asBinder(),
            ),
            startupLogListener = null,
        )
        assertEquals(1, oldServer.deathRecipientCount)

        val replacementInfo = PrivilegeServerInfo(
            uid = 2000,
            pid = 5678,
            protocolVersion = PrivilegeProtocol.VERSION,
            lifecycleBinder = android.os.Binder(),
        )
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = replacementInfo,
                serverBinder = newServer.asBinder(),
            ),
            startupLogListener = null,
        )

        assertEquals(0, oldServer.deathRecipientCount)
        assertEquals(1, newServer.deathRecipientCount)
        assertSame(replacementInfo, Privilege.getServerInfo())
    }

    @Test
    fun rootServerIsNeverPermissionRestrictedWithoutPermissionCheck() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 0,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
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
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 1000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
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
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 5678,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
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
    fun deniedServerPermissionsReturnsServerSnapshot() {
        val server = FakePrivilegeServer(
            deniedServerPermissions = arrayOf(
                "android.permission.GRANT_RUNTIME_PERMISSIONS",
                "android.permission.INJECT_EVENTS",
            ),
        )
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

        assertEquals(
            listOf(
                "android.permission.GRANT_RUNTIME_PERMISSIONS",
                "android.permission.INJECT_EVENTS",
            ),
            Privilege.getDeniedServerPermissions(),
        )
        assertEquals(1, server.deniedServerPermissionQueries)
    }

    @Test
    fun rootServerHasNoDeniedPermissionsWithoutServerQuery() {
        val server = FakePrivilegeServer(
            deniedServerPermissions = arrayOf("permission.SHOULD_NOT_BE_RETURNED"),
        )
        Privilege.connectHandshake(
            handshakeResult = testHandshakeResult(
                serverInfo = PrivilegeServerInfo(
                    uid = 0,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                    lifecycleBinder = android.os.Binder(),
                ),
                serverBinder = server.asBinder(),
            ),
            startupLogListener = null,
        )

        assertEquals(emptyList<String>(), Privilege.getDeniedServerPermissions())
        assertEquals(0, server.deniedServerPermissionQueries)
    }

    @Test
    fun deniedServerPermissionsWithoutConnectionThrowsDisconnectedException() {
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.getDeniedServerPermissions()
        }
    }

    @Test
    fun checkPermissionReturnsPackageManagerResult() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )
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
    fun revokeRuntimePermissionPassesThroughWithoutServerPermissionCheck() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_DENIED,
        )
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

        Privilege.revokeRuntimePermission(
            packageName = "test.package",
            permissionName = "android.permission.WRITE_SECURE_SETTINGS",
            userId = 10,
        )

        assertEquals(
            listOf(
                RuntimePermissionRevoke(
                    packageName = "test.package",
                    permissionName = "android.permission.WRITE_SECURE_SETTINGS",
                    userId = 10,
                ),
            ),
            server.runtimePermissionRevokes,
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
                    lifecycleBinder = android.os.Binder(),
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
                    lifecycleBinder = android.os.Binder(),
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
        private val deniedServerPermissions: Array<String> = emptyArray(),
    ) : IPrivilegeServer {
        private val binder = TestBinder(localInterface = this)
        val serverPermissionChecks = mutableListOf<String>()
        val packagePermissionChecks = mutableListOf<PackagePermissionCheck>()
        val runtimePermissionGrants = mutableListOf<RuntimePermissionGrant>()
        val runtimePermissionRevokes = mutableListOf<RuntimePermissionRevoke>()
        val runtimeConfigUpdates = mutableListOf<RuntimeConfigUpdate>()
        val ownerRestartTimeouts = mutableListOf<Long>()
        var deniedServerPermissionQueries = 0
            private set
        val deathRecipientCount: Int
            get() = binder.deathRecipientCount

        fun killBinder() {
            binder.killBinder(notifyDeathRecipients = false)
        }

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun hasSystemService(serviceName: String): Boolean = false

        override fun checkServerPermission(permission: String): Int {
            serverPermissionChecks += permission
            return checkServerPermissionCall?.invoke(permission) ?: permissionResult
        }

        override fun getDeniedServerPermissions(): Array<String> {
            deniedServerPermissionQueries += 1
            return deniedServerPermissions.copyOf()
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

        override fun revokeRuntimePermission(
            packageName: String,
            permissionName: String,
            userId: Int,
        ) {
            runtimePermissionRevokes += RuntimePermissionRevoke(
                packageName = packageName,
                permissionName = permissionName,
                userId = userId,
            )
        }

        override fun updateRuntimeConfig(
            followDeathDelayMillis: Long,
            activeReconnectOnOwnerDeath: Boolean,
        ) {
            runtimeConfigUpdates += RuntimeConfigUpdate(
                followDeathDelayMillis = followDeathDelayMillis,
                activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
            )
        }

        override fun prepareOwnerRestart(passiveReconnectTimeoutMillis: Long) {
            ownerRestartTimeouts += passiveReconnectTimeoutMillis
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

    private data class RuntimePermissionRevoke(
        val packageName: String,
        val permissionName: String,
        val userId: Int,
    )

    private data class RuntimeConfigUpdate(
        val followDeathDelayMillis: Long,
        val activeReconnectOnOwnerDeath: Boolean,
    )
}
