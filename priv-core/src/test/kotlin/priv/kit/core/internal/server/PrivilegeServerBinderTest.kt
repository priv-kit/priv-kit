package priv.kit.core.internal.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
        assertNotSame(server.asBinder(), serviceEndpoints.commandExecutorBinder)
        assertNull(
            lifecycleBinder.queryLocalInterface(
                "priv.kit.core.internal.binder.IPrivilegeServer",
            ),
        )
    }

    @Test
    fun runtimeConfigUpdateDelegatesCompleteSnapshot() {
        var received: RuntimeConfigUpdate? = null
        val server = PrivilegeServerBinder(
            config = PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                classpath = "/data/app/priv.kit.sample/base.apk",
            ),
            onRuntimeConfigChanged = { followDeathDelayMillis, activeReconnectOnOwnerDeath ->
                received = RuntimeConfigUpdate(
                    followDeathDelayMillis = followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
                )
            },
        )

        server.updateRuntimeConfig(
            followDeathDelayMillis = 42_000L,
            activeReconnectOnOwnerDeath = true,
        )

        assertEquals(
            RuntimeConfigUpdate(
                followDeathDelayMillis = 42_000L,
                activeReconnectOnOwnerDeath = true,
            ),
            received,
        )
    }

    @Test
    fun prepareOwnerRestartDelegatesPassiveReconnectTimeout() {
        var received: PreparedOwnerRestart? = null
        val server = PrivilegeServerBinder(
            config = PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                classpath = "/data/app/priv.kit.sample/base.apk",
            ),
            onOwnerRestartPrepared = { timeoutMillis, ownerPid ->
                received = PreparedOwnerRestart(timeoutMillis, ownerPid)
            },
            callingPidProvider = { 4321 },
        )

        server.prepareOwnerRestart(passiveReconnectTimeoutMillis = 8_000L)

        assertEquals(
            PreparedOwnerRestart(
                passiveReconnectTimeoutMillis = 8_000L,
                ownerPid = 4321,
            ),
            received,
        )
    }

    @Test
    fun prepareOwnerRestartRejectsNonPositiveTimeout() {
        val server = PrivilegeServerBinder(
            PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                classpath = "/data/app/priv.kit.sample/base.apk",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            server.prepareOwnerRestart(passiveReconnectTimeoutMillis = 0L)
        }
    }

    @Test
    fun prepareOwnerRestartRejectsInvalidCallingPid() {
        val server = PrivilegeServerBinder(
            config = PrivilegeServerConfig(
                packageName = "priv.kit.sample",
                classpath = "/data/app/priv.kit.sample/base.apk",
            ),
            callingPidProvider = { 0 },
        )

        assertThrows(IllegalArgumentException::class.java) {
            server.prepareOwnerRestart(passiveReconnectTimeoutMillis = 8_000L)
        }
    }

    private data class RuntimeConfigUpdate(
        val followDeathDelayMillis: Long,
        val activeReconnectOnOwnerDeath: Boolean,
    )

    private data class PreparedOwnerRestart(
        val passiveReconnectTimeoutMillis: Long,
        val ownerPid: Int,
    )
}
