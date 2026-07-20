package priv.kit.internal.runtime

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.internal.core.PrivilegeHandshakeContract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeOwnerProcessNotifierTest {
    @Test
    fun schedulePostsOwnerProcessStartedNotification() {
        val application = RuntimeEnvironment.getApplication()
        var postedBlock: (() -> Unit)? = null
        var notifiedContext: Context? = null
        var notifiedUri: Uri? = null

        PrivilegeOwnerProcessNotifier.schedule(
            context = application,
            post = { block -> postedBlock = block },
            notifyChange = { context, uri, _ ->
                notifiedContext = context
                notifiedUri = uri
            },
        )

        assertNotNull(postedBlock)
        assertNull(notifiedUri)

        requireNotNull(postedBlock).invoke()

        assertEquals(application.applicationContext, notifiedContext)
        assertEquals(
            PrivilegeHandshakeContract.ownerProcessStartedUri(application.packageName),
            notifiedUri,
        )
    }

    @Test
    fun notificationFailureDoesNotEscapePostedWork() {
        val application = RuntimeEnvironment.getApplication()

        PrivilegeOwnerProcessNotifier.schedule(
            context = application,
            post = { block -> block() },
            notifyChange = { _, _, _ -> error("notify failed") },
        )
    }
}
