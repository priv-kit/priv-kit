package priv.kit.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.state.PrivilegeUiViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiExternalStartActionsTest {
    @Test
    fun authorizationCallHoldsInteractivePermitUntilProviderReturns() {
        val provider = BlockingAuthorizationExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(store = store, coroutineScope = scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            store.config = config
            store.initializeState(config)
            val authorization = executor.submit {
                actions.authorizeOrStartExternal(provider.id)
            }

            assertTrue(provider.authorizationStarted.await(2, TimeUnit.SECONDS))
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            provider.releaseAuthorization.countDown()
            authorization.get(2, TimeUnit.SECONDS)
            val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
            assertNotNull(silentPermit)
            silentPermit!!.close()
        } finally {
            provider.releaseAuthorization.countDown()
            executor.shutdownNow()
            actions.close()
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun silentOwnerPreventsExternalProviderCalls() {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(store = store, coroutineScope = scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
        )
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        try {
            store.config = config
            store.initializeState(config)

            actions.refreshExternalStartStatusNow(stop = null, providerId = null)
            actions.authorizeOrStartExternal(provider.id)
            actions.directStartAttempt(provider.id)

            assertEquals(0, provider.snapshotCalls.get())
        } finally {
            checkNotNull(silentPermit).close()
            actions.close()
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun refreshBeforeAttachUsesConstructionContext() {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
        )
        try {
            store.config = PrivilegeUiConfig(externalStartProviders = listOf(provider))

            val refreshed = actions.refreshExternalStartStatusNow(stop = null, providerId = null)

            assertTrue(refreshed)
            assertEquals(1, provider.snapshotCalls.get())
        } finally {
            actions.close()
            runtimeActions.close()
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
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
            createShellStartCommand = { _ -> "external command" },
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
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun silentStartDuringAuthorizedSnapshotDoesNotDropPendingExternalStart() {
        val provider = DeferredAuthorizationExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val acquireInteractivePermit = PrivilegeUiStartGate.newInteractivePermitAcquirer()
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
            acquireStartPermit = acquireInteractivePermit,
        )
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
            createShellStartCommand = { _ -> "external command" },
            acquireInteractivePermit = acquireInteractivePermit,
        )
        val executor = Executors.newSingleThreadExecutor()
        var silentPermit: AutoCloseable? = null
        try {
            store.config = config
            store.initializeState(config)
            actions.authorizeOrStartExternal(provider.id)
            provider.authorized = true
            provider.blockAuthorizedSnapshot = true

            val refresh = executor.submit<Boolean> {
                actions.refreshExternalStartStatusNow(stop = null, providerId = provider.id)
            }
            assertTrue(provider.authorizedSnapshotStarted.await(2, TimeUnit.SECONDS))
            silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
            assertNotNull(silentPermit)
            provider.releaseAuthorizedSnapshot.countDown()
            assertTrue(refresh.get(2, TimeUnit.SECONDS))
            assertEquals(0, provider.startCalls.get())

            checkNotNull(silentPermit).close()
            silentPermit = null
            provider.blockAuthorizedSnapshot = false
            actions.refreshExternalStartStatusNow(stop = null, providerId = provider.id)

            assertTrue(provider.started.await(2, TimeUnit.SECONDS))
            assertEquals(1, provider.startCalls.get())
        } finally {
            provider.releaseAuthorizedSnapshot.countDown()
            silentPermit?.close()
            executor.shutdownNow()
            actions.close()
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun externalStartFailureUsesLocalizedMessageAndKeepsDiagnosticLog() = runBlocking {
        val provider = ThrowingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            coroutineScope = scope,
            createShellStartCommand = { _ -> "external command" },
        )
        try {
            store.config = config
            store.initializeState(config)
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000.milliseconds) { store.snackbarMessages.first() }
            }

            actions.authorizeOrStartExternal(provider.id)

            assertEquals("外部授权启动失败，请查看启动日志", snackbar.await())
            withTimeout(2_000.milliseconds) {
                store.state.first { state ->
                    state.startupLogLines.any { "Injected external provider failure" in it }
                }
            }
            Unit
        } finally {
            actions.close()
            runtimeActions.close()
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
        val authorizedSnapshotStarted = CountDownLatch(1)
        val releaseAuthorizedSnapshot = CountDownLatch(1)
        @Volatile
        var authorized: Boolean = false
        @Volatile
        var blockAuthorizedSnapshot: Boolean = false
        @Volatile
        var startedCommandLine: String? = null

        override val id: String = "external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            if (authorized && blockAuthorizedSnapshot) {
                authorizedSnapshotStarted.countDown()
                releaseAuthorizedSnapshot.await(30, TimeUnit.SECONDS)
            }
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = authorized,
            )
        }

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

    private class BlockingAuthorizationExternalStartProvider : PrivilegeUiExternalStartProvider {
        val authorizationStarted = CountDownLatch(1)
        val releaseAuthorization = CountDownLatch(1)

        override val id: String = "blocking-external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(available = true)

        override fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot {
            authorizationStarted.countDown()
            releaseAuthorization.await(30, TimeUnit.SECONDS)
            return snapshot(context)
        }

        override fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private class ThrowingExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "throwing-external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
            )

        override fun start(
            context: Context,
            commandLine: String,
        ): Unit = error("Injected external provider failure")
    }
}
