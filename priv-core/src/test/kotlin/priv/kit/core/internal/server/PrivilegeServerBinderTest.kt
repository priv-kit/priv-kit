package priv.kit.core.internal.server

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeServerBinderTest {
    @Test
    fun lifecycleBinderIsStableAndDoesNotExposeServerControlInterface() {
        val server = PrivilegeServerBinder(
            PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                classpath = "/data/app/priv.kit.sample/base.apk",
            ),
        )

        val lifecycleBinder = server.lifecycleBinder

        assertSame(lifecycleBinder, server.lifecycleBinder)
        assertNotSame(server.asBinder(), lifecycleBinder)
        assertNull(
            lifecycleBinder.queryLocalInterface(
                "priv.kit.core.internal.binder.IPrivilegeServer",
            ),
        )
    }
}
