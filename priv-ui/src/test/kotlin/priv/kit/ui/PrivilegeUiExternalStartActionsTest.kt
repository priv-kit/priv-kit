package priv.kit.ui

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiExternalStartActionsTest {
    @Test
    fun authorizationCallHoldsInteractivePermitUntilProviderReturns() = runBlocking {
        val provider = BlockingAuthorizationExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(store = store, coroutineScope = scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
        )
        try {
            store.config = config
            store.initializeState(config)
            val authorization = async(Dispatchers.Default) {
                actions.authorizeOrStartExternal(provider.id)
            }

            withTimeout(2_000.milliseconds) { provider.authorizationStarted.await() }
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            provider.releaseAuthorization.complete(Unit)
            authorization.await()
            val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
            assertNotNull(silentPermit)
            silentPermit!!.close()
        } finally {
            provider.releaseAuthorization.complete(Unit)
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun silentOwnerPreventsExternalProviderCalls() = runBlocking {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(store = store, coroutineScope = scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
        )
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        try {
            store.config = config
            store.initializeState(config)

            actions.refreshExternalStartStatusNow(providerId = null)
            actions.authorizeOrStartExternal(provider.id)
            actions.directStartAttempt(provider.id)

            assertEquals(0, provider.snapshotCalls.get())
        } finally {
            checkNotNull(silentPermit).close()
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun refreshBeforeAttachUsesConstructionContext() = runBlocking {
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
        )
        try {
            store.config = PrivilegeUiConfig(externalStartProviders = listOf(provider))

            val refreshed = actions.refreshExternalStartStatusNow(providerId = null)

            assertTrue(refreshed)
            assertEquals(1, provider.snapshotCalls.get())
        } finally {
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun snapshotCancellationIsNotConvertedIntoFailureState() = runBlocking {
        val provider = CancellingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(store = store, coroutineScope = scope)
        val actions = PrivilegeUiExternalStartActions(store = store, runtimeActions = runtimeActions)
        try {
            store.config = config
            store.initializeState(config)

            val failure = runCatching {
                actions.refreshExternalStartStatusNow(providerId = provider.id)
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        } finally {
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun suspendingAuthorizationContinuesDirectlyFromItsCallbackResult() = runBlocking {
        val provider = SuspendingAuthorizationExternalStartProvider()
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
            createNativeStarterCommand = { _ -> "external command" },
        )
        try {
            store.config = config
            store.initializeState(config)

            val authorization = async(start = CoroutineStart.UNDISPATCHED) {
                actions.authorizeOrStartExternal(provider.id)
            }

            assertEquals(1, provider.authorizationCalls.get())
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            provider.authorizationResult.complete(true)
            authorization.await()

            assertEquals(
                "external command",
                withTimeout(2_000.milliseconds) { provider.startedCommandLine.await() },
            )
        } finally {
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
            createNativeStarterCommand = { _ -> "external command" },
        )
        try {
            store.config = config
            store.initializeState(config)
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000.milliseconds) {
                    store.snackbarTexts.first().asString(store.requireContext())
                }
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
            runtimeActions.close()
            scope.cancel()
            store.close()
        }
    }

    private class CountingExternalStartProvider : PrivilegeUiExternalStartProvider {
        val snapshotCalls = AtomicInteger(0)

        override val id: String = "external"
        override val label: CharSequence = "External"

        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            snapshotCalls.incrementAndGet()
            return PrivilegeUiExternalStartSnapshot()
        }

        override suspend fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private class CancellingExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "cancelling-external"
        override val label: CharSequence = "External"

        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            throw CancellationException("cancelled snapshot")

        override suspend fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private class BlockingAuthorizationExternalStartProvider : PrivilegeUiExternalStartProvider {
        val authorizationStarted = CompletableDeferred<Unit>()
        val releaseAuthorization = CompletableDeferred<Unit>()

        override val id: String = "blocking-external"
        override val label: CharSequence = "External"

        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(available = true)

        override suspend fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot {
            authorizationStarted.complete(Unit)
            releaseAuthorization.await()
            return snapshot(context)
        }

        override suspend fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private class SuspendingAuthorizationExternalStartProvider :
        PrivilegeUiExternalStartProvider {
        val authorizationResult = CompletableDeferred<Boolean>()
        val authorizationCalls = AtomicInteger(0)
        val startedCommandLine = CompletableDeferred<String>()
        @Volatile
        var authorized: Boolean = false

        override val id: String = "suspending-external"
        override val label: CharSequence = "External"

        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = authorized,
            )

        override suspend fun requestAuthorization(
            context: Context,
        ): PrivilegeUiExternalStartSnapshot {
            authorizationCalls.incrementAndGet()
            authorized = authorizationResult.await()
            return snapshot(context)
        }

        override suspend fun start(
            context: Context,
            commandLine: String,
        ) {
            startedCommandLine.complete(commandLine)
        }
    }

    private class ThrowingExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "throwing-external"
        override val label: CharSequence = "External"

        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
            PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
            )

        override suspend fun start(
            context: Context,
            commandLine: String,
        ): Unit = error("Injected external provider failure")
    }

}
