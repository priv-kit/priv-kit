package priv.kit

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
import priv.kit.binder.PrivilegeServerDisconnectedException
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
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
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
        assertThrows(PrivilegeServerDisconnectedException::class.java) {
            Privilege.getServerInfo()
        }
    }

    private class FakePrivilegeServer : IPrivilegeServer {
        private val binder = FakeBinder(localInterface = this)

        fun killBinder() {
            binder.kill()
        }

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun hasSystemService(serviceName: String?): Boolean = false
    }

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
