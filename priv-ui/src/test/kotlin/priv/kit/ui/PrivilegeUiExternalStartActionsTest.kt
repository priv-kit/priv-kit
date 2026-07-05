package priv.kit.ui

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiExternalStartActionsTest {
    @Test
    fun refreshBeforeAttachUsesConstructionContext() {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = PrivilegeUiRuntimeActions(
                store = store,
                coroutineScope = scope,
            ),
            coroutineScope = scope,
        )
        try {
            store.config = PrivilegeUiConfig(externalStartProviders = listOf(provider))

            val refreshed = actions.refreshExternalStartStatusNow(stop = null, providerId = null)

            assertTrue(refreshed)
            assertEquals(1, provider.snapshotCalls.get())
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun pendingExternalAuthorizationStartsAfterAuthorizedRefresh() {
        val provider = DeferredAuthorizationExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = PrivilegeUiRuntimeActions(
                store = store,
                coroutineScope = scope,
            ),
            coroutineScope = scope,
            createShellStartCommand = { "external command" },
        )
        try {
            store.config = config
            store.initializeState(config)

            actions.authorizeOrStartExternal(provider.id)

            assertEquals(1, provider.requestAuthorizationCalls.get())
            assertEquals(0, provider.startCalls.get())

            provider.authorized = true
            actions.refreshExternalStartStatusNow(stop = null, providerId = provider.id)

            assertTrue(provider.started.await(2, TimeUnit.SECONDS))
            assertEquals(1, provider.startCalls.get())
            assertEquals("external command", provider.startedCommandLine)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    private class CountingExternalStartProvider : PrivilegeUiExternalStartProvider {
        val snapshotCalls = AtomicInteger(0)

        override val id: String = "external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            snapshotCalls.incrementAndGet()
            return PrivilegeUiExternalStartSnapshot()
        }

        override fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private class DeferredAuthorizationExternalStartProvider : PrivilegeUiExternalStartProvider {
        val requestAuthorizationCalls = AtomicInteger(0)
        val startCalls = AtomicInteger(0)
        val started = CountDownLatch(1)
        @Volatile
        var authorized: Boolean = false
        @Volatile
        var startedCommandLine: String? = null

        override val id: String = "external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = authorized,
            )

        override fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot {
            requestAuthorizationCalls.incrementAndGet()
            return snapshot(context)
        }

        override fun start(
            context: Context,
            commandLine: String,
        ) {
            startedCommandLine = commandLine
            startCalls.incrementAndGet()
            started.countDown()
        }
    }
}
