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
import priv.kit.ui.adb.updateConfiguredTcpModePort
import priv.kit.ui.adb.updateTcpModePort
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
    fun automaticTimeoutWithoutHostTransitionUsesPersistentDialog() = runBlocking {
        assertAuthorizationFailureBoundary(
            endReason = PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
            diagnosticMessage = "ADB key authorization did not complete",
            expectedMessage = null,
            expectedStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            observeSystemPrompt = false,
            expectedDialogMessage =
                "系统未显示 ADB 密钥弹窗，请关闭其他授权工具或重启 ADB 后重试",
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

    @Test
    fun staticTcpControlClosesAndRestartsTheActivePort() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = runtimeScope,
            acquireStartPermit = { AutoCloseable {} },
        )
        var stoppedPort: Int? = null
        var restartedPort: Int? = null
        var refreshCount = 0
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            runtimeActions = runtimeActions,
            refreshTcpModeEnabled = { refreshCount += 1 },
            tcpModeStopper = { tcpPort ->
                stoppedPort = tcpPort
            },
            tcpModeRestarter = { tcpPort ->
                restartedPort = tcpPort
                tcpPort
            },
        )
        try {
            store.updateTcpModePort(5555)
            store.updateConfiguredTcpModePort(5555)

            tcpActions.disableTcpMode()

            assertTrue(waitUntilIdle(store))
            assertEquals(5555, stoppedPort)
            assertEquals(null, store.state.value.tcpModePort)
            assertEquals(null, store.state.value.configuredTcpModePort)
            assertTrue(waitUntil {
                store.state.value.startupLogLines.any {
                    "静态端口已停止" in it
                }
            })

            store.updateTcpModePort(5555)
            store.updateConfiguredTcpModePort(5555)

            tcpActions.restartTcpMode()

            assertTrue(waitUntilIdle(store))
            assertEquals(5555, restartedPort)
            assertEquals(5555, store.state.value.tcpModePort)
            assertEquals(5555, store.state.value.configuredTcpModePort)
            assertTrue(waitUntil {
                store.state.value.startupLogLines.any {
                    "静态端口已重启" in it
                }
            })
            assertEquals(2, refreshCount)
        } finally {
            runtimeActions.close()
            runtimeScope.cancel()
            store.close()
        }
    }

    @Test
    fun staticTcpControlFallsBackToWirelessAfterStaticConnectionFailure() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = runtimeScope,
            acquireStartPermit = { AutoCloseable {} },
        )
        val attempts = mutableListOf<String>()
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            runtimeActions = runtimeActions,
            refreshTcpModeEnabled = {},
            tcpModeStopper = {
                attempts += "static-stop"
                error("static stop failed")
            },
            wirelessTcpModeStopper = { tcpPort, options ->
                attempts += "wireless-stop:$tcpPort:${options.port}"
            },
            tcpModeRestarter = {
                attempts += "static-restart"
                error("static restart failed")
            },
            wirelessTcpModeRestarter = { tcpPort, options ->
                attempts += "wireless-restart:$tcpPort:${options.port}"
                tcpPort
            },
        )
        try {
            store.updateTcpModePort(5555)
            store.updateConfiguredTcpModePort(5555)

            tcpActions.disableTcpMode()

            assertTrue(waitUntilIdle(store))
            assertEquals(null, store.state.value.tcpModePort)

            store.updateTcpModePort(5555)
            store.updateConfiguredTcpModePort(5555)

            tcpActions.restartTcpMode()

            assertTrue(waitUntilIdle(store))
            assertEquals(5555, store.state.value.tcpModePort)
            assertEquals(
                listOf(
                    "static-stop",
                    "wireless-stop:5555:null",
                    "static-restart",
                    "wireless-restart:5555:null",
                ),
                attempts,
            )
            assertEquals(
                2,
                store.state.value.startupLogLines.count {
                    "静态端口连接失败，正在尝试无线调试" in it
                },
            )
        } finally {
            runtimeActions.close()
            runtimeScope.cancel()
            store.close()
        }
    }

    private suspend fun assertAuthorizationFailureBoundary(
        endReason: PrivilegeAdbAuthorizationEndReason,
        diagnosticMessage: String,
        expectedMessage: String?,
        expectedStatus: PrivilegeUiAdbTcpAuthorizationStatus,
        observeSystemPrompt: Boolean = true,
        expectedDialogMessage: String? = null,
    ) = coroutineScope {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeActions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = runtimeScope,
            acquireStartPermit = { AutoCloseable {} },
        )
        val authorizationResult = CompletableDeferred<PrivilegeAdbAuthorizationRequestResult>()
        val authorizationRequestStarted = CompletableDeferred<Unit>()
        var requestedAuthorizationTimeoutMillis: Long? = null
        val promptCoordinator = PrivilegeUiSystemPromptCoordinator().also {
            it.registerHost("host", resumed = true, hasWindowFocus = true)
        }
        val tcpActions = PrivilegeUiAdbTcpActions(
            store = store,
            runtimeActions = runtimeActions,
            refreshTcpModeEnabled = {},
            systemPromptCoordinator = promptCoordinator,
            tcpAuthorizationRequester = { _, timeoutMillis ->
                requestedAuthorizationTimeoutMillis = timeoutMillis
                authorizationRequestStarted.complete(Unit)
                authorizationResult.await()
            },
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
            assertTrue(store.state.value.startupLogLines.any {
                "如果没有弹窗，可能有其他授权请求占用" in it
            })
            authorizationRequestStarted.await()
            assertEquals(15_000L, requestedAuthorizationTimeoutMillis)
            if (observeSystemPrompt) {
                promptCoordinator.onHostPaused("host")
                assertEquals(
                    R.string.priv_ui_system_prompt_tcp_authorization_title,
                    (promptCoordinator.visiblePrompt.value?.title as PrivilegeUiText.Resource).id,
                )
            }
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
            assertEquals(
                expectedDialogMessage,
                store.state.value.tcpAuthorizationFailureDialogText
                    ?.asString(store.requireContext()),
            )
            if (observeSystemPrompt) {
                assertTrue(promptCoordinator.visiblePrompt.value != null)
                promptCoordinator.onHostResumed("host", hasWindowFocus = true)
            }
            assertEquals(null, promptCoordinator.visiblePrompt.value)
        } finally {
            runtimeActions.close()
            promptCoordinator.close()
            runtimeScope.cancel()
            store.close()
        }
    }
}
