package priv.kit.internal.runtime

import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeOrigin
import priv.kit.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.internal.core.PrivilegeServerHandshakeResult
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeHandshakeProviderTest {
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
    fun tokenlessHandshakeIsDeliveredAsInitialLaunch() {
        val application = prepareRuntimeApplication()
        val token = PrivilegeOwnerTokenStore.readOrCreate()
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
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
            assertEquals(token, response.getString(PrivilegeHandshakeContract.RESULT_TOKEN))
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
    fun persistedTokenHandshakeIsDeliveredAsOwnerReconnect() {
        prepareRuntimeApplication()
        val token = PrivilegeOwnerTokenStore.readOrCreate()
        val serverBinder = Binder()
        val received = AtomicReference<PrivilegeServerHandshakeResult?>()
        val listener = PrivilegeServerHandshakeRegistry.addReadyListener(token) { result ->
            received.set(result)
            true
        }

        try {
            val response = PrivilegeHandshakeProvider().call(
                PrivilegeHandshakeContract.METHOD_SERVER_READY,
                token,
                currentHandshakeExtras(serverBinder).apply {
                    putString(PrivilegeHandshakeContract.EXTRA_TOKEN, token)
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
    fun staleServerWithValidTokenReceivesReplacementCommand() {
        prepareRuntimeApplication()
        val token = PrivilegeOwnerTokenStore.readOrCreate()

        val response = PrivilegeHandshakeProvider().call(
            PrivilegeHandshakeContract.METHOD_SERVER_READY,
            token,
            Bundle().apply {
                putString(PrivilegeHandshakeContract.EXTRA_TOKEN, token)
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
            "${PrivilegeHandshakeContract.ENV_INITIAL_LAUNCH_ID}='' " +
                "/data/app/priv.kit.sample-current/lib/arm64/libprivkitstarter.so",
            response.getString(PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND),
        )
    }

    private fun prepareRuntimeApplication() =
        RuntimeEnvironment.getApplication().also { application ->
            application.applicationInfo.sourceDir = "/data/app/priv.kit.sample-current/base.apk"
            application.applicationInfo.nativeLibraryDir =
                "/data/app/priv.kit.sample-current/lib/arm64"
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
}
