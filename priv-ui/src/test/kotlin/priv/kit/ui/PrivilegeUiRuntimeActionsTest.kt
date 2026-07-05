package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.PrivilegeServerInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiRuntimeActionsTest {
    @Test
    fun failedConnectStartKeepsExistingConnectionStatus() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        try {
            store.connectAsShell()

            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "root",
                    startupSource = null,
                ) {
                    error("root unavailable")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertEquals("root unavailable", store.state.value.serviceMessage)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun handledConnectStartFailureDoesNotMarkRuntimeFailed() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        try {
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "adb",
                    startupSource = null,
                    onFailure = {
                        store.updateState { current ->
                            current.copy(
                                busy = false,
                                runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                                serviceMessage = "handled",
                            )
                        }
                        true
                    },
                ) {
                    error("ADB key is not authorized")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertEquals("handled", store.state.value.serviceMessage)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun failedRequestStartKeepsExistingConnectionStatus() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        try {
            store.connectAsShell()

            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "requested",
                    startupSource = null,
                ) {
                    error("external unavailable")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertEquals("external unavailable", store.state.value.serviceMessage)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun failedFallbackStartKeepsExistingConnectionStatus() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        try {
            store.connectAsShell()

            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        error("root unavailable")
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        error("adb unavailable")
                    },
                ),
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertEquals("adb unavailable", store.state.value.serviceMessage)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun fallbackStartContinuesAfterFailedAttempt() = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        val secondAttemptStarted = CountDownLatch(1)
        try {
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        error("root unavailable")
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        secondAttemptStarted.countDown()
                        PrivilegeServerInfo(
                            uid = 2000,
                            pid = 1234,
                            protocolVersion = 1,
                        )
                    },
                ),
            )

            assertTrue(secondAttemptStarted.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntilConnected(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun stopCurrentStartInterruptsRunningStartAndKeepsStoppedState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = PrivilegeUiViewModelStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
        )
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        try {
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "adb",
                    startupSource = null,
                ) {
                    started.countDown()
                    try {
                        CountDownLatch(1).await(30, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        interrupted.countDown()
                        throw e
                    }
                    error("start was not stopped")
                },
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()

            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertEquals(
                context.getString(R.string.priv_ui_startup_stopped),
                store.state.value.serviceMessage,
            )
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    private suspend fun waitUntilConnected(store: PrivilegeUiViewModelStore): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            store.state.first {
                it.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
            }
            true
        } ?: false

    private suspend fun waitUntilIdle(store: PrivilegeUiViewModelStore): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            store.state.first {
                !it.busy
            }
            true
        } ?: false

    private fun PrivilegeUiViewModelStore.connectAsShell() {
        updateState {
            it.copy(
                runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED,
                serverInfo = PrivilegeServerInfo(
                    uid = 2000,
                    pid = 1234,
                    protocolVersion = 1,
                ),
                serviceMessage = "connected",
            )
        }
    }
}
