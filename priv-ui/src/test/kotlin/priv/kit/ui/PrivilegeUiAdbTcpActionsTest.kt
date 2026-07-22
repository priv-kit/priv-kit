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
import kotlinx.coroutines.delay
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
@Config(sdk = [36], qualifiers = "zh-rCN")
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
        val runtimeActions = PrivilegeUiRuntimeActions(store, runtimeScope)
        val authorizationResult = CompletableDeferred<PrivilegeAdbAuthorizationRequestResult>()
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            runtimeActions = runtimeActions,
            refreshTcpModeEnabled = {},
            tcpAuthorizationRequester = { _, _ -> authorizationResult.await() },
        )
        try {
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
                    store.snackbarMessages.first()
                }
            }

            runtimeActions.runServerStartWorkflow(
                PrivilegeUiRuntimeStartAttempt.Workflow(
                    message = "tcp",
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
        } finally {
            runtimeActions.close()
            runtimeScope.cancel()
            store.close()
        }
    }

    private suspend fun waitUntilIdle(store: PrivilegeUiViewModelStore): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            store.state.first {
                !it.busy && it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE
            }
            true
        } ?: false

    private suspend fun waitUntil(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            while (!condition()) delay(10L)
            true
        } ?: false
}
