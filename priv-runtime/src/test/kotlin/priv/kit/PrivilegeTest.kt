package priv.kit

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.internal.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeServerUnavailableException
import priv.kit.internal.core.PrivilegeAndroidUsers
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeResult
import priv.kit.internal.runtime.PrivilegeServerLaunchCommandBuilder
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

class PrivilegeTest {
    @After
    fun clearServer() {
        runCatching { Privilege.shutdownServer() }
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

        val identity = PrivilegeServerLaunchCommandBuilder.buildClasspathIdentity(apk.path)

        assertTrue(identity.contains(apk.path))
        assertTrue(identity.contains("@${apk.length()}@${apk.lastModified() / 1000L}"))
    }

    @Test
    fun userIdIsDerivedFromAndroidUidRange() {
        assertEquals(0, PrivilegeAndroidUsers.userIdFromUid(10_123))
        assertEquals(10, PrivilegeAndroidUsers.userIdFromUid(1_012_345))
    }

    @Test
    fun shortNativeStarterCommandUsesStarterPathOnly() {
        val commandLine = Privilege.buildShortNativeStarterCommand(
            starterPath = "/data/app/example/lib/arm64/libprivkitstarter.so",
        )

        assertEquals(
            "/data/app/example/lib/arm64/libprivkitstarter.so",
            commandLine,
        )
        assertFalse(commandLine.contains("token-value"))
        assertFalse(commandLine.contains("--user-id"))
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
            PrivilegeServerHandshakeResult(
                token = "token",
                serverInfo = serverInfo,
                serverBinder = server.asBinder(),
            ),
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
            PrivilegeServerHandshakeResult(
                token = "token",
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
        )

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            Privilege.checkServerPermission("android.permission.GRANT_RUNTIME_PERMISSIONS"),
        )
    }

    @Test
    fun checkPermissionReturnsPackageManagerResult() {
        val server = FakePrivilegeServer(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )
        Privilege.connectHandshake(
            PrivilegeServerHandshakeResult(
                token = "token",
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
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
            PrivilegeServerHandshakeResult(
                token = "token",
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = PrivilegeProtocol.VERSION,
                ),
                serverBinder = server.asBinder(),
            ),
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
    ) : IPrivilegeServer {
        private val binder = FakeBinder(localInterface = this)
        val serverPermissionChecks = mutableListOf<String>()
        val packagePermissionChecks = mutableListOf<PackagePermissionCheck>()
        val runtimePermissionGrants = mutableListOf<RuntimePermissionGrant>()

        fun killBinder() {
            binder.kill()
        }

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun hasSystemService(serviceName: String): Boolean = false

        override fun checkServerPermission(permission: String): Int {
            serverPermissionChecks += permission
            return permissionResult
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

    private class FakeBinder(
        private val localInterface: IInterface? = null,
        @Volatile
        private var alive: Boolean = true,
    ) : IBinder {
        private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()

        fun kill() {
            alive = false
        }

        override fun getInterfaceDescriptor(): String = "fake"

        override fun pingBinder(): Boolean = alive

        override fun isBinderAlive(): Boolean = alive

        override fun queryLocalInterface(descriptor: String): IInterface? = localInterface

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
