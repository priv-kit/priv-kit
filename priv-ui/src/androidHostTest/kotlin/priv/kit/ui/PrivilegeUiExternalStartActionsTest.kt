package priv.kit.ui

import android.content.Context
import android.content.res.Configuration
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
import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeExternalStartResult
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.state.PrivilegeUiViewModelStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiExternalStartActionsTest {
    @Test
    @Config(qualifiers = "en")
    fun authorizationCallHoldsInteractivePermitUntilProviderReturns() = runBlocking {
        val provider = BlockingAuthorizationExternalStartProvider()
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val application = RuntimeEnvironment.getApplication()
        val store = PrivilegeUiViewModelStore(application, config)
        assertEquals("Start", store.externalStartLabel(provider.id).toString())
        val chineseContext = application.createConfigurationContext(
            Configuration(application.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
        )
        store.refreshExternalStartLabels(chineseContext)
        assertEquals("启动", store.externalStartLabel(provider.id).toString())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val promptCoordinator = PrivilegeUiAuthorizationCoordinator()
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            authorizationCoordinator = promptCoordinator,
        )
        try {
            val authorization = async(Dispatchers.Default) {
                actions.authorizeOrStartExternal(provider.id)
            }

            withTimeout(2_000.milliseconds) { provider.authorizationStarted.await() }
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            assertEquals(PrivilegeUiAuthorizationKind.EXTERNAL, promptCoordinator.authorization.value?.kind)
            assertEquals("启动", promptCoordinator.authorization.value?.providerLabel)

            provider.releaseAuthorization.complete(Unit)
            authorization.await()
            assertEquals(false, store.state.value.externalStartItems.single().authorized)
            assertTrue(store.state.value.startupLogLines.any { "Authorization request denied" in it })
            assertNull(promptCoordinator.authorization.value)
            val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
            assertNotNull(silentPermit)
            silentPermit!!.close()
        } finally {
            provider.releaseAuthorization.complete(Unit)
            runtimeActions.close()
            promptCoordinator.close()
            scope.cancel()

        }
    }

    @Test
    fun silentOwnerPreventsExternalProviderCalls() = runBlocking {
        val provider = CountingExternalStartProvider()
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication(), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
        )
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        try {
            actions.refreshExternalStartStatusNow(providerId = null)
            actions.authorizeOrStartExternal(provider.id)
            actions.directStartAttempt(provider.id)

            assertEquals(0, provider.snapshotCalls.get())
        } finally {
            checkNotNull(silentPermit).close()
            runtimeActions.close()
            scope.cancel()

        }
    }

    @Test
    fun refreshBeforeAttachUsesConstructionContext() = runBlocking {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(
            context = RuntimeEnvironment.getApplication(),
            config = PrivilegeUiConfig(externalStartProviders = listOf(provider)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
        )
        try {
            val refreshed = actions.refreshExternalStartStatusNow(providerId = null)

            assertTrue(refreshed)
            assertEquals(1, provider.snapshotCalls.get())
            val item = store.state.value.externalStartItems.single()
            assertTrue(item.statusLoaded)
            assertNull(item.authorized)
            assertTrue(store.state.value.startupLogLines.isEmpty())
        } finally {
            runtimeActions.close()
            scope.cancel()

        }
    }

    @Test
    fun snapshotCancellationIsNotConvertedIntoFailureState() = runBlocking {
        val provider = CancellingExternalStartProvider()
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication(), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val actions = PrivilegeUiExternalStartActions(store = store, runtimeActions = runtimeActions)
        try {
            val failure = runCatching {
                actions.refreshExternalStartStatusNow(providerId = provider.id)
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        } finally {
            runtimeActions.close()
            scope.cancel()

        }
    }

    @Test
    fun suspendingAuthorizationContinuesDirectlyFromItsCallbackResult() = runBlocking {
        val provider = SuspendingAuthorizationExternalStartProvider()
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication(), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            createNativeStarterCommand = { _ -> "external command" },
        )
        try {
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

        }
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun externalStartFailureUsesLocalizedMessageAndKeepsDiagnosticLog() = runBlocking {
        val provider = ThrowingExternalStartProvider()
        val config = PrivilegeUiConfig(externalStartProviders = listOf(provider))
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication(), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = newRuntimeActions(store, scope)
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = runtimeActions,
            createNativeStarterCommand = { _ -> "external command" },
        )
        try {
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

        }
    }

    private fun newRuntimeActions(
        store: PrivilegeUiViewModelStore,
        scope: CoroutineScope,
    ): PrivilegeUiRuntimeActions =
        PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
            acquireStartPermit = { AutoCloseable {} },
        )

    private class CountingExternalStartProvider : PrivilegeExternalStartProvider {
        val snapshotCalls = AtomicInteger(0)

        override val id: String = "external"
        override fun getLabel(context: Context): CharSequence = "External"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult {
            snapshotCalls.incrementAndGet()
            return PrivilegeExternalStartResult(
                authorized = null,
                message = "External service is unavailable",
            )
        }

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
            error("Unexpected authorization request")

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Unexpected start")
    }

    private class CancellingExternalStartProvider : PrivilegeExternalStartProvider {
        override val id: String = "cancelling-external"
        override fun getLabel(context: Context): CharSequence = "External"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            throw CancellationException("cancelled snapshot")

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
            error("Unexpected authorization request")

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Unexpected start")
    }

    private class BlockingAuthorizationExternalStartProvider : PrivilegeExternalStartProvider {
        val authorizationStarted = CompletableDeferred<Unit>()
        val releaseAuthorization = CompletableDeferred<Unit>()

        override val id: String = "blocking-external"
        override fun getLabel(context: Context): CharSequence = if (context.resources.configuration.locales[0].language == "zh") "启动" else "Start"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            PrivilegeExternalStartResult(authorized = false)

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult {
            authorizationStarted.complete(Unit)
            releaseAuthorization.await()
            return PrivilegeExternalStartResult(authorized = false, message = "Authorization request denied")
        }

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Unexpected start")
    }

    private class SuspendingAuthorizationExternalStartProvider :
        PrivilegeExternalStartProvider {
        val authorizationResult = CompletableDeferred<Boolean>()
        val authorizationCalls = AtomicInteger(0)
        val startedCommandLine = CompletableDeferred<String>()
        @Volatile
        var authorized: Boolean = false

        override val id: String = "suspending-external"
        override fun getLabel(context: Context): CharSequence = "External"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            PrivilegeExternalStartResult(authorized = authorized)

        override suspend fun requestAuthorization(
            context: Context,
        ): PrivilegeExternalStartResult {
            authorizationCalls.incrementAndGet()
            authorized = authorizationResult.await()
            return checkAuthorization(context)
        }

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String {
            checkNotNull(startupLogListener)
            startedCommandLine.complete(commandLine)
            return ""
        }
    }

    private class ThrowingExternalStartProvider : PrivilegeExternalStartProvider {
        override val id: String = "throwing-external"
        override fun getLabel(context: Context): CharSequence = "External"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            PrivilegeExternalStartResult(authorized = true)

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
            error("Unexpected authorization request")

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Injected external provider failure")
    }

}
