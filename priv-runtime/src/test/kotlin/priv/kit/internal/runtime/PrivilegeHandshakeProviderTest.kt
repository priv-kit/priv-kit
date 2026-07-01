package priv.kit.internal.runtime

import android.os.Binder
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.core.PrivilegeProtocol

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeHandshakeProviderTest {
    @Test
    fun staleServerWithValidTokenReceivesReplacementCommand() {
        val application = RuntimeEnvironment.getApplication()
        application.applicationInfo.sourceDir = "/data/app/priv.kit.sample-current/base.apk"
        application.applicationInfo.nativeLibraryDir = "/data/app/priv.kit.sample-current/lib/arm64"
        application.applicationInfo.splitSourceDirs = null
        PrivilegeContext.install(application)
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
            "/data/app/priv.kit.sample-current/lib/arm64/libprivkitstarter.so",
            response.getString(PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND),
        )
    }
}
