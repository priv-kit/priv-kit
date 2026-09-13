package priv.kit.ui

import java.util.concurrent.TimeUnit
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
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartSession
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "b+zh+Hans")
class PrivilegeUiAdbTcpActionsTest {
    @Test
    fun defaultAuthorizationTimeoutIsFifteenSeconds() {
        assertEquals(15_000L, PrivilegeUiConfig().adbAuthorizationTimeoutMillis)
    }

    @Test
    fun automaticTimeoutUsesPersistentDialog() = runBlocking {
        assertAuthorizationFailureBoundary(
            result = authorizationRequestResult(
                authorized = false,
                endReason = PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
            ),
            expectedMessage = null,
            expectedStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            expectedDialogVisible = true,
        )
    }

    @Test
    fun failedAuthorizationUsesLocalizedSnackbarAndKeepsDiagnosticLog() = runBlocking {
        assertAuthorizationFailureBoundary(
            result = authorizationRequestResult(
                authorized = false,
                endReason = PrivilegeAdbAuthorizationEndReason.FAILED,
                failureMessage = "Connection refused",
            ),
            expectedMessage = "ADB 授权失败，请重试",
            expectedStatus = PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
            expectedDialogVisible = false,
        )
    }

    private suspend fun assertAuthorizationFailureBoundary(
        result: PrivilegeAdbAuthorizationRequestResult,
        expectedMessage: String?,
        expectedStatus: PrivilegeUiAdbTcpAuthorizationStatus,
        expectedDialogVisible: Boolean,
    ) = coroutineScope {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = runtimeScope,
            acquireStartPermit = { AutoCloseable {} },
        )
        val promptCoordinator = PrivilegeUiSystemPromptCoordinator()
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            systemPromptCoordinator = promptCoordinator,
        )
        try {
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(TimeUnit.MILLISECONDS.toMillis(500)) {
                    store.snackbarTexts.first().asString(store.requireContext())
                }
            }
            runtimeActions.runServerStartWorkflow(
                PrivilegeUiRuntimeStartAttempt.Workflow(
                    progressText = PrivilegeUiText.Literal("tcp"),
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
                ) {
                    tcpActions.applyAuthorizationResultForTest(this, result)
                    PrivilegeUiRuntimeStartResult.Finished
                },
            )

            assertEquals(expectedMessage, snackbar.await())
            assertTrue(waitUntilIdle(store))
            assertEquals(expectedStatus, store.state.value.staticTcp.authorizationStatus)
            result.failureMessage?.let { diagnostic ->
                assertTrue(store.state.value.startupLogLines.any { diagnostic in it })
            }
            assertEquals(
                expectedDialogVisible,
                store.state.value.tcpAuthorizationFailureDialogVisible,
            )
        } finally {
            runtimeActions.close()
            promptCoordinator.close()
            runtimeScope.cancel()
            store.close()
        }
    }

    private fun authorizationRequestResult(
        authorized: Boolean,
        endReason: PrivilegeAdbAuthorizationEndReason?,
        outputText: String = "",
        failureMessage: String? = null,
    ): PrivilegeAdbAuthorizationRequestResult =
        PrivilegeAdbAuthorizationRequestResult::class.java.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            PrivilegeAdbAuthorizationEndReason::class.java,
            String::class.java,
            String::class.java,
        ).newInstance(authorized, endReason, outputText, failureMessage)

    private fun PrivilegeUiAdbTcpActions.applyAuthorizationResultForTest(
        session: PrivilegeUiRuntimeStartSession,
        result: PrivilegeAdbAuthorizationRequestResult,
    ) {
        val method = PrivilegeUiAdbTcpActions::class.java.getDeclaredMethod(
            "applyTcpAuthorizationResult",
            PrivilegeUiRuntimeStartSession::class.java,
            PrivilegeAdbAuthorizationRequestResult::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(this, session, result, true)
    }
}
