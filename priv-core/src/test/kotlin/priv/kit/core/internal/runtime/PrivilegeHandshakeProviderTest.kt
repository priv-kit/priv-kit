package priv.kit.core.internal.runtime

import android.database.ContentObserver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin
import priv.kit.core.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.testing.TestBinder
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeHandshakeProviderTest {
    @After
    fun clearServer() {
        runCatching { Privilege.shutdownServer() }
    }

    @Test
    fun onCreateNotifiesOwnerProcessStarted() {
        val application = RuntimeEnvironment.getApplication()
        val expectedUri = PrivilegeHandshakeContract.ownerProcessStartedUri(application.packageName)
        val notifiedUri = AtomicReference<Uri?>()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                notifiedUri.set(uri)
            }
        }
        application.contentResolver.registerContentObserver(expectedUri, false, observer)

        try {
            Robolectric.buildContentProvider(PrivilegeHandshakeProvider::class.java)
                .create()
                .get()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(expectedUri, notifiedUri.get())
        } finally {
            application.contentResolver.unregisterContentObserver(observer)
        }
    }

    @Test
    fun handshakeIsDeliveredAsInitialLaunch() {
        prepareRuntimeApplication()
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            val response = PrivilegeHandshakeProvider().call(
                PrivilegeHandshakeContract.METHOD_SERVER_READY,
                null,
                currentHandshakeExtras(serverBinder),
            )

            assertNotNull(response)
            assertTrue(response!!.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false))
            assertSame(serverBinder, received.get()?.serverBinder)
            assertEquals(
                PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
                received.get()?.origin,
            )
        } finally {
            listener.close()
        }
    }

    @Test
    fun initialHandshakeRejectsWhileExistingServerBinderIsAlive() {
        prepareRuntimeApplication()
        val existingServer = FakePrivilegeServer()
        val existingServerInfo = connectServer(existingServer)
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            val response = PrivilegeHandshakeProvider().call(
                PrivilegeHandshakeContract.METHOD_SERVER_READY,
                null,
                currentHandshakeExtras(serverBinder),
            )

            assertNotNull(response)
            assertFalse(response!!.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true))
            assertNull(received.get())
            assertEquals(existingServerInfo, Privilege.getServerInfo())
        } finally {
            listener.close()
        }
    }

    @Test
    fun initialHandshakeCanReplaceDeadConnectedState() {
        prepareRuntimeApplication()
        val existingServer = FakePrivilegeServer()
        connectServer(existingServer)
        existingServer.killBinder()
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            val response = PrivilegeHandshakeProvider().call(
                PrivilegeHandshakeContract.METHOD_SERVER_READY,
                null,
                currentHandshakeExtras(serverBinder),
            )

            assertNotNull(response)
            assertTrue(response!!.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false))
            assertSame(serverBinder, received.get()?.serverBinder)
        } finally {
            listener.close()
        }
    }

    @Test
    fun explicitReconnectHandshakeIsDeliveredAsOwnerReconnect() {
        prepareRuntimeApplication()
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener { result ->
            received.set(result)
            true
        }

        try {
            val response = PrivilegeHandshakeProvider().call(
                PrivilegeHandshakeContract.METHOD_SERVER_READY,
                null,
                currentHandshakeExtras(serverBinder).apply {
                    putBoolean(PrivilegeHandshakeContract.EXTRA_OWNER_RECONNECT, true)
                },
            )

            assertNotNull(response)
            assertTrue(response!!.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false))
            assertSame(serverBinder, received.get()?.serverBinder)
            assertEquals(
                PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
                received.get()?.origin,
            )
        } finally {
            listener.close()
        }
    }

    @Test
    fun staleTrustedServerReceivesReplacementCommand() {
        prepareRuntimeApplication()
        val nativeStarterCommand = Privilege.nativeStarterCommand

        val response = PrivilegeHandshakeProvider().call(
            PrivilegeHandshakeContract.METHOD_SERVER_READY,
            null,
            Bundle().apply {
                putBoolean(PrivilegeHandshakeContract.EXTRA_OWNER_RECONNECT, true)
                putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, Binder())
                putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, PrivilegeProtocol.VERSION)
                putString(
                    PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY,
                    "/data/app/priv.kit.sample-old/base.apk@1@1",
                )
            },
        )

        assertNotNull(response)
        assertFalse(response!!.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, true))
        assertEquals(
            nativeStarterCommand,
            response.getString(PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND),
        )
    }

    private fun prepareRuntimeApplication() =
        RuntimeEnvironment.getApplication().also { application ->
            application.applicationInfo.sourceDir = "/data/app/priv.kit.sample-current/base.apk"
            application.applicationInfo.nativeLibraryDir = File(
                application.cacheDir,
                "handshake-provider-native/arm64",
            ).apply {
                mkdirs()
                File(this, "libprivkitstarter.so").writeBytes(byteArrayOf(1))
            }.path.replace('\\', '/')
            application.applicationInfo.splitSourceDirs = null
            PrivilegeContext.install(application)
        }

    private fun currentHandshakeExtras(serverBinder: Binder): Bundle =
        Bundle().apply {
            putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, serverBinder)
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, PrivilegeProtocol.VERSION)
            putString(
                PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY,
                PrivilegeHandshakeContract.classpathIdentity(
                    PrivilegeServerLaunchCommandBuilder.buildClasspath(),
                ),
            )
        }

    private fun connectServer(server: FakePrivilegeServer): PrivilegeServerInfo {
        val serverInfo = PrivilegeServerInfo(
            uid = 0,
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
        return serverInfo
    }

    private class FakePrivilegeServer : IPrivilegeServer {
        private val binder = TestBinder(localInterface = this)
        private val lifecycleBinder = TestBinder()

        override fun asBinder(): IBinder = binder

        override fun shutdown() = Unit

        override fun getUserServiceManager(): IBinder? = null

        override fun getLifecycleBinder(): IBinder = lifecycleBinder

        override fun hasSystemService(serviceName: String): Boolean = false

        override fun checkServerPermission(permission: String): Int =
            PackageManager.PERMISSION_GRANTED

        override fun checkPermission(
            permName: String,
            pkgName: String,
            userId: Int,
        ): Int = PackageManager.PERMISSION_GRANTED

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

        fun killBinder() {
            binder.killBinder(notifyDeathRecipients = false)
        }
    }
}
