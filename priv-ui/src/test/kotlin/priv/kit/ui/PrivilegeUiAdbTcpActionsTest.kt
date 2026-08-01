package priv.kit.ui

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.core.adb.PrivilegeAdbAuthorizationRequestResult
import priv.kit.ui.adb.PrivilegeUiAdbTcpActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartResult
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "b+zh+Hans")
class PrivilegeUiAdbTcpActionsTest {
    @Test
    fun automaticTimeoutUsesLocalizedSnackbarAndKeepsDiagnosticLog() = runBlocking {
        assertAuthorizationFailureBoundary(
            endReason = PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
            diagnosticMessage = "ADB key authorization did not complete",
            expectedMessage = "还没有完成系统确认",
            expectedStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
        )
    }

    @Test
    fun failedAuthorizationUsesLocalizedSnackbarAndKeepsDiagnosticLog() = runBlocking {
        assertAuthorizationFailureBoundary(
            endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
            diagnosticMessage = "Connection refused",
            expectedMessage = "ADB 授权失败，请重试",
            expectedStatus = PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
        )
    }

    private suspend fun assertAuthorizationFailureBoundary(
        endReason: PrivilegeAdbAuthorizationEndReason,
        diagnosticMessage: String,
        expectedMessage: String,
        expectedStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    ) = coroutineScope {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = runtimeScope,
            acquireStartPermit = { AutoCloseable {} },
        )
        val authorizationResult = CompletableDeferred<PrivilegeAdbAuthorizationRequestResult>()
        val promptCoordinator = PrivilegeUiSystemPromptCoordinator().also {
            it.registerHost("host", resumed = true, hasWindowFocus = true)
        }
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            runtimeActions = runtimeActions,
            refreshTcpModeEnabled = {},
            systemPromptCoordinator = promptCoordinator,
            tcpAuthorizationRequester = { _, _ -> authorizationResult.await() },
        )
        try {
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
                    store.snackbarTexts.first().asString(store.requireContext())
                }
            }

            runtimeActions.runServerStartWorkflow(
                PrivilegeUiRuntimeStartAttempt.Workflow(
                    progressText = PrivilegeUiText.Literal("tcp"),
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
                ) {
                    tcpActions.requestTcpAuthorizationForStart(this, tcpPort = 5555)
                    PrivilegeUiRuntimeStartResult.Finished
                },
            )

            assertTrue(waitUntil {
                store.state.value.tcpAuthorizationStatus ==
                    PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
            })
            promptCoordinator.onHostPaused("host")
            assertEquals(
                R.string.priv_ui_system_prompt_tcp_authorization_title,
                (promptCoordinator.visiblePrompt.value?.title as PrivilegeUiText.Resource).id,
            )
            authorizationResult.complete(
                PrivilegeAdbAuthorizationRequestResult(
                    authorized = false,
                    endReason = endReason,
                    failureMessage = diagnosticMessage,
                ),
            )

            assertEquals(expectedMessage, snackbar.await())
            assertTrue(waitUntilIdle(store))
            assertEquals(expectedStatus, store.state.value.tcpAuthorizationStatus)
            assertTrue(store.state.value.startupLogLines.any { diagnosticMessage in it })
            assertTrue(promptCoordinator.visiblePrompt.value != null)
            promptCoordinator.onHostResumed("host", hasWindowFocus = true)
            assertEquals(null, promptCoordinator.visiblePrompt.value)
        } finally {
            runtimeActions.close()
            promptCoordinator.close()
            runtimeScope.cancel()
            store.close()
        }
    }
}
