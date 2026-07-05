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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiRuntimeActionsTest {
    @Test
    fun failedConnectStartKeepsExistingConnectionStatus() {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val actions = PrivilegeUiRuntimeActions(store)
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
            store.close()
        }
    }

    @Test
    fun failedRequestStartKeepsExistingConnectionStatus() {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val actions = PrivilegeUiRuntimeActions(store)
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
            store.close()
        }
    }

    @Test
    fun failedFallbackStartKeepsExistingConnectionStatus() {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val actions = PrivilegeUiRuntimeActions(store)
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
            store.close()
        }
    }

    @Test
    fun fallbackStartContinuesAfterFailedAttempt() {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val actions = PrivilegeUiRuntimeActions(store)
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
            store.close()
        }
    }

    @Test
    fun stopCurrentStartInterruptsRunningStartAndKeepsStoppedState() {
        val context = RuntimeEnvironment.getApplication()
        val store = PrivilegeUiViewModelStore(context)
        val actions = PrivilegeUiRuntimeActions(store)
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
                        Thread.sleep(TimeUnit.SECONDS.toMillis(30))
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
            store.close()
        }
    }

    private fun waitUntilConnected(store: PrivilegeUiViewModelStore): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun waitUntilIdle(store: PrivilegeUiViewModelStore): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (!store.state.value.busy) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }

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
