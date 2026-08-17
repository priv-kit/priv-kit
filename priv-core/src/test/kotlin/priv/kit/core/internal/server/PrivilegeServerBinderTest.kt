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
        val serviceEndpoints = server.serviceEndpoints

        assertSame(lifecycleBinder, server.lifecycleBinder)
        assertSame(serviceEndpoints, server.serviceEndpoints)
        assertNotSame(server.asBinder(), lifecycleBinder)
        assertNotSame(server.asBinder(), serviceEndpoints.fileSystemBinder)
        assertNotSame(server.asBinder(), serviceEndpoints.userServiceManagerBinder)
        assertNull(
            lifecycleBinder.queryLocalInterface(
                "priv.kit.core.internal.binder.IPrivilegeServer",
            ),
        )
    }
}
