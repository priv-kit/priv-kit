package priv.kit.ui

import android.content.Context
import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeServerLaunchUncertainException
import priv.kit.PrivilegeStartupException
import priv.kit.adb.PrivilegeAdbAuthorizationRequestResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineDispatcher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiRuntimeActionsTest {
    @Test
    fun silentOwnerRejectsUiBusySideEffects() {
        RuntimeActionsFixture().use { (store, actions) ->
            val actionCalls = AtomicInteger(0)
            val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
            try {
                actions.runBusy(
                    message = "blocked",
                    failureKind = PrivilegeUiFailureKind.TCP_ENABLE_FAILED,
                    action = actionCalls::incrementAndGet,
                    onSuccess = { "unexpected" },
                )

                assertEquals(0, actionCalls.get())
                assertFalse(store.state.value.busy)
                assertTrue(store.state.value.startupLogLines.isEmpty())
            } finally {
                silentPermit.close()
            }
        }
    }

    @Test
    fun queuedBusyActionKeepsSilentStartBlockedUntilCompletion() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val blockerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val actionCompleted = CountDownLatch(1)
        blockerScope.launch {
            blockerStarted.countDown()
            releaseBlocker.await(30, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))
        try {
            RuntimeActionsFixture(
                acquireStartPermit = PrivilegeUiStartGate.newInteractivePermitAcquirer(),
                operationDispatcher = dispatcher,
                beforeClose = releaseBlocker::countDown,
            ).use { (store, actions) ->
                actions.runBusy(
                    message = "queued",
                    failureKind = PrivilegeUiFailureKind.TCP_ENABLE_FAILED,
                    action = actionCompleted::countDown,
                    onSuccess = { "done" },
                )

                assertTrue(store.state.value.busy)
                assertNull(PrivilegeUiStartGate.tryAcquireSilent())

                releaseBlocker.countDown()
                assertTrue(actionCompleted.await(2, TimeUnit.SECONDS))
                assertTrue(waitUntilIdle(store))
                assertTrue(waitUntil { PrivilegeUiStartGate.state.value.owner == null })
                PrivilegeUiStartGate.tryAcquireSilent()!!.close()
            }
        } finally {
            releaseBlocker.countDown()
            blockerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun queuedStopKeepsSilentStartBlockedUntilShutdownCompletes() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val blockerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val shutdownCompleted = CountDownLatch(1)
        blockerScope.launch {
            blockerStarted.countDown()
            releaseBlocker.await(30, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))
        try {
            RuntimeActionsFixture(
                shutdownServer = shutdownCompleted::countDown,
                acquireStartPermit = PrivilegeUiStartGate.newInteractivePermitAcquirer(),
                operationDispatcher = dispatcher,
                beforeClose = releaseBlocker::countDown,
            ).use { (store, actions) ->
                actions.connectForTest(shellServerInfo())
                actions.stopServer()

                assertTrue(store.state.value.busy)
                assertNull(PrivilegeUiStartGate.tryAcquireSilent())

                releaseBlocker.countDown()
                assertTrue(shutdownCompleted.await(2, TimeUnit.SECONDS))
                assertTrue(waitUntilIdle(store))
                assertTrue(waitUntil { PrivilegeUiStartGate.state.value.owner == null })
                PrivilegeUiStartGate.tryAcquireSilent()!!.close()
            }
        } finally {
            releaseBlocker.countDown()
            blockerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun rootUnavailableSnackbarIsLocalizedWithoutRewritingDiagnosticLog() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = store.text(R.string.priv_ui_starting_root),
                    startupSource = store.text(R.string.priv_ui_auth_method_root),
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
                ) {
                    throw PrivilegeStartupException("Root is not available")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.FAILED, store.state.value.runtimeStatus)
            assertEquals("Root 权限不可用", snackbar.await())
            assertTrue(
                store.state.value.startupLogLines.any { "Root is not available" in it },
            )
            assertFalse(
                store.state.value.startupLogLines.any { "Root 权限不可用" in it },
            )
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun unknownRootStartFailureUsesLocalizedFallbackAndKeepsDiagnosticLog() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = store.text(R.string.priv_ui_starting_root),
                    startupSource = store.text(R.string.priv_ui_auth_method_root),
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ROOT,
                ) {
                    throw PrivilegeStartupException("Failed to execute su")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.FAILED, store.state.value.runtimeStatus)
            assertEquals("Root 启动失败，请查看启动日志", snackbar.await())
            assertTrue(
                store.state.value.startupLogLines.any { "Failed to execute su" in it },
            )
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun adbStartFailureUsesLocalizedFallbackAndKeepsDiagnosticLog() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = store.text(R.string.priv_ui_wireless_adb_starting),
                    startupSource = store.text(R.string.priv_ui_auth_method_adb),
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
                ) {
                    throw PrivilegeStartupException("Failed to discover ADB connect port")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.FAILED, store.state.value.runtimeStatus)
            assertEquals("ADB 启动失败，请查看启动日志", snackbar.await())
            assertTrue(
                store.state.value.startupLogLines.any {
                    "Failed to discover ADB connect port" in it
                },
            )
        }
    }

    @Test
    fun connectionForegroundRefreshAndDisconnectUpdateAdbRestrictionStatus() = runBlocking {
        val restricted = AtomicBoolean(true)
        RuntimeActionsFixture(
            isAdbPermissionRestricted = restricted::get,
        ).use { (store, actions) ->
            actions.connectForTest(shellServerInfo())

            assertTrue(waitUntil {
                store.state.value.adbRestrictionStatus ==
                    PrivilegeUiAdbRestrictionStatus.RESTRICTED
            })

            restricted.set(false)
            actions.refreshAdbPermissionRestrictionStatus()

            assertTrue(waitUntil {
                store.state.value.adbRestrictionStatus ==
                    PrivilegeUiAdbRestrictionStatus.NOT_RESTRICTED
            })

            actions.disconnectForTest()

            assertEquals(
                PrivilegeUiAdbRestrictionStatus.UNKNOWN,
                store.state.value.adbRestrictionStatus,
            )
        }
    }

    @Test
    fun staleAdbRestrictionRefreshCannotOverwriteNewerResult() = runBlocking {
        val firstRefreshEntered = CountDownLatch(1)
        val releaseFirstRefresh = CountDownLatch(1)
        val refreshCount = AtomicInteger(0)
        RuntimeActionsFixture(
            isAdbPermissionRestricted = {
                if (refreshCount.incrementAndGet() == 1) {
                    firstRefreshEntered.countDown()
                    releaseFirstRefresh.await(2, TimeUnit.SECONDS)
                    true
                } else {
                    false
                }
            },
        ).use { (store, actions) ->
            try {
                actions.connectForTest(shellServerInfo())
                assertTrue(firstRefreshEntered.await(2, TimeUnit.SECONDS))

                actions.refreshAdbPermissionRestrictionStatus()
                assertTrue(waitUntil {
                    store.state.value.adbRestrictionStatus ==
                        PrivilegeUiAdbRestrictionStatus.NOT_RESTRICTED
                })

                releaseFirstRefresh.countDown()
                delay(50L)

                assertEquals(
                    PrivilegeUiAdbRestrictionStatus.NOT_RESTRICTED,
                    store.state.value.adbRestrictionStatus,
                )
            } finally {
                releaseFirstRefresh.countDown()
            }
        }
    }

    @Test
    fun failedAdbRestrictionRefreshPreservesLastKnownStatus() = runBlocking {
        val failRefresh = AtomicBoolean(false)
        val refreshCount = AtomicInteger(0)
        RuntimeActionsFixture(
            isAdbPermissionRestricted = {
                refreshCount.incrementAndGet()
                if (failRefresh.get()) error("restriction check failed")
                true
            },
        ).use { (store, actions) ->
            actions.connectForTest(shellServerInfo())
            assertTrue(waitUntil {
                store.state.value.adbRestrictionStatus ==
                    PrivilegeUiAdbRestrictionStatus.RESTRICTED
            })

            failRefresh.set(true)
            actions.refreshAdbPermissionRestrictionStatus()
            assertTrue(waitUntil { refreshCount.get() >= 2 })

            assertEquals(
                PrivilegeUiAdbRestrictionStatus.RESTRICTED,
                store.state.value.adbRestrictionStatus,
            )
        }
    }

    @Test
    fun staleDisconnectedRefreshCannotOverwriteNewConnection() {
        RuntimeActionsFixture().use { (store, actions) ->
            val observedConnectionSerial = store.state.value.connectionSerial
            actions.connectForTest(shellServerInfo())

            actions.updateDisconnectedIfIdleForTest(observedConnectionSerial)

            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertEquals(observedConnectionSerial + 1L, store.state.value.connectionSerial)
        }
    }

    @Test
    fun duplicatePassiveConnectionDoesNotIncrementConnectionSerial() {
        RuntimeActionsFixture().use { (store, actions) ->
            val serverInfo = shellServerInfo()
            actions.connectForTest(serverInfo)
            val connectedSerial = store.state.value.connectionSerial

            actions.connectForTest(serverInfo)

            assertEquals(connectedSerial, store.state.value.connectionSerial)
        }
    }

    @Test
    fun stopServerClaimsOperationAtomically() = runBlocking {
        val shutdownStarted = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val shutdownCount = AtomicInteger(0)
        RuntimeActionsFixture(
            shutdownServer = {
                shutdownCount.incrementAndGet()
                shutdownStarted.countDown()
                releaseShutdown.await(30, TimeUnit.SECONDS)
            },
        ).use { (store, actions) ->
            try {
                actions.connectForTest(shellServerInfo())

                actions.stopServer()
                actions.stopServer()

                assertTrue(shutdownStarted.await(2, TimeUnit.SECONDS))
                assertEquals(1, shutdownCount.get())
                releaseShutdown.countDown()
                assertTrue(waitUntilIdle(store))
                assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            } finally {
                releaseShutdown.countDown()
            }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun stopServerFailureUsesLocalizedMessageAndKeepsDiagnosticLog() = runBlocking {
        RuntimeActionsFixture(
            shutdownServer = { error("Privilege server is unavailable") },
        ).use { (store, actions) ->
            actions.connectForTest(shellServerInfo())
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.stopServer()

            assertTrue(waitUntilIdle(store))
            assertEquals("停止特权服务失败，请重试", snackbar.await())
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertTrue(waitUntil {
                store.state.value.startupLogLines.any { "Privilege server is unavailable" in it }
            })
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun busyFailureUsesOperationMessageAndKeepsDiagnosticLog() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runBusy(
                message = store.text(R.string.priv_ui_tcp_enabling),
                failureKind = PrivilegeUiFailureKind.TCP_ENABLE_FAILED,
                action = { error("Failed to switch ADB to TCP mode: injected transcript") },
                onSuccess = { store.text(R.string.priv_ui_tcp_enabled) },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals("无法打开静态 ADB 端口，请重试", snackbar.await())
            assertTrue(waitUntil {
                store.state.value.startupLogLines.any {
                    "Failed to switch ADB to TCP mode: injected transcript" in it
                }
            })
        }
    }

    @Test
    fun staleStopCompletionCannotOverwriteNewConnection() = runBlocking {
        val shutdownStarted = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val shutdownReturned = CountDownLatch(1)
        RuntimeActionsFixture(
            shutdownServer = {
                shutdownStarted.countDown()
                releaseShutdown.await(30, TimeUnit.SECONDS)
                shutdownReturned.countDown()
            },
        ).use { (store, actions) ->
            try {
                actions.connectForTest(shellServerInfo())
                actions.stopServer()
                assertTrue(shutdownStarted.await(2, TimeUnit.SECONDS))

                actions.connectForTest(
                    PrivilegeServerInfo(
                        uid = 0,
                        pid = 5678,
                        protocolVersion = 1,
                    ),
                )
                releaseShutdown.countDown()

                assertTrue(shutdownReturned.await(2, TimeUnit.SECONDS))
                assertTrue(waitUntil { !store.serverShutdownRequestedByOwner })
                assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
                assertEquals(0, store.state.value.serverInfo?.uid)
            } finally {
                releaseShutdown.countDown()
            }
        }
    }

    @Test
    fun staleTcpAuthorizationResultCannotOverwriteNextSession() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val callbacks = CopyOnWriteArrayList<(PrivilegeAdbAuthorizationRequestResult) -> Unit>()
            val firstCommitEntered = CountDownLatch(1)
            val releaseFirstCommit = CountDownLatch(1)
            val firstCommit = AtomicBoolean(true)
            val tcpActions = PrivilegeUiAdbTcpActions(
                store = store,
                runtimeActions = actions,
                refreshTcpModeEnabled = {},
                tcpAuthorizationRequester = { _, _, callback ->
                    callbacks += callback
                    AutoCloseable {}
                },
                beforeTcpAuthorizationResultCommit = {
                    if (firstCommit.compareAndSet(true, false)) {
                        firstCommitEntered.countDown()
                        releaseFirstCommit.await(30, TimeUnit.SECONDS)
                    }
                },
            )

            fun startAuthorization() {
                actions.runServerStartWorkflow(
                    PrivilegeUiRuntimeStartAttempt.Workflow(
                        message = "tcp",
                        startupSource = null,
                    ) {
                        tcpActions.requestTcpAuthorizationForStart(this, tcpPort = 5555)
                        PrivilegeUiRuntimeStartResult.Finished
                    },
                )
            }

            try {
                startAuthorization()
                assertTrue(waitUntil { callbacks.size == 1 })
                val staleCallback = callbacks.single()
                val staleCommit = async(Dispatchers.Default) {
                    staleCallback(PrivilegeAdbAuthorizationRequestResult(authorized = true))
                }
                assertTrue(firstCommitEntered.await(2, TimeUnit.SECONDS))

                val cancellation = async(Dispatchers.Default) {
                    actions.stopCurrentStart()
                }
                assertTrue(
                    waitUntil {
                        store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.CANCELLING
                    },
                )
                startAuthorization()
                assertEquals(1, callbacks.size)

                releaseFirstCommit.countDown()
                cancellation.await()
                staleCommit.await()
                assertTrue(waitUntilIdle(store))

                startAuthorization()
                assertTrue(waitUntil { callbacks.size == 2 })
                assertEquals(
                    PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                    store.state.value.tcpAuthorizationStatus,
                )
                val currentRequest = store.tcpAuthorizationRequest
                assertNotNull(currentRequest)

                staleCallback(PrivilegeAdbAuthorizationRequestResult(authorized = true))

                assertSame(currentRequest, store.tcpAuthorizationRequest)
                assertEquals(
                    PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                    store.state.value.tcpAuthorizationStatus,
                )
                assertFalse(
                    store.state.value.startupLogLines.contains(
                        store.text(R.string.priv_ui_tcp_authorization_allowed),
                    ),
                )
            } finally {
                releaseFirstCommit.countDown()
                actions.stopCurrentStart()
                tcpActions.close()
            }
        }
    }

    @Test
    fun connectedRuntimeRunsConnectStartAndPreservesExistingConnectionOnFailure() = runBlocking {
        val attempted = AtomicBoolean(false)
        val afterCommitCalled = AtomicBoolean(false)
        val userActionCalled = AtomicBoolean(false)
        RuntimeActionsFixture().use { (store, actions) ->
            store.connectAsShell()

            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "root",
                    startupSource = null,
                    onFailure = {
                        PrivilegeUiRuntimeStartFailureDisposition(
                            afterCommit = { afterCommitCalled.set(true) },
                            onUserActionRequired = { userActionCalled.set(true) },
                        )
                    },
                ) {
                    attempted.set(true)
                    error("root unavailable")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertTrue(waitUntil { userActionCalled.get() })
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertNull(store.state.value.runtimeProgressMessage)
            assertTrue(attempted.get())
            assertTrue(afterCommitCalled.get())
        }
    }

    @Test
    fun userActionPermitHandoffDoesNotExposeSilentStartGap() = runBlocking {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val followUpPermit = AtomicReference<AutoCloseable?>(null)
        val acquireInteractivePermit = PrivilegeUiStartGate.newInteractivePermitAcquirer()
        try {
            RuntimeActionsFixture(
                acquireStartPermit = acquireInteractivePermit,
                beforeClose = releaseCallback::countDown,
            ).use { (store, actions) ->
                actions.runServerStart(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                        onFailure = {
                            PrivilegeUiRuntimeStartFailureDisposition(
                                onUserActionRequired = {
                                    callbackEntered.countDown()
                                    releaseCallback.await(30, TimeUnit.SECONDS)
                                    followUpPermit.set(
                                        acquireInteractivePermit(),
                                    )
                                },
                            )
                        },
                    ) {
                        error("local network permission required")
                    },
                )

                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
                assertNull(PrivilegeUiStartGate.tryAcquireSilent())

                releaseCallback.countDown()
                assertTrue(waitUntil { followUpPermit.get() != null })
                assertTrue(waitUntilIdle(store))
                assertNull(PrivilegeUiStartGate.tryAcquireSilent())

                followUpPermit.getAndSet(null)!!.close()
                assertTrue(waitUntil { PrivilegeUiStartGate.state.value.owner == null })
            }
        } finally {
            releaseCallback.countDown()
            followUpPermit.getAndSet(null)?.close()
        }
    }

    @Test
    fun handledConnectStartFailureDoesNotMarkRuntimeFailed() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "adb",
                    startupSource = null,
                    onFailure = {
                        PrivilegeUiRuntimeStartFailureDisposition(
                            snackbarMessage = "handled",
                        )
                    },
                ) {
                    error("ADB key is not authorized")
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertNull(store.state.value.runtimeProgressMessage)
            assertEquals("handled", snackbar.await())
        }
    }

    @Test
    fun connectedRuntimeRunsRequestStartAndReportsReplacementTimeout() = runBlocking {
        val attempted = AtomicBoolean(false)
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 50L)
            },
        ).use { (store, actions) ->
            store.connectAsShell()
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "requested",
                    startupSource = null,
                ) {
                    attempted.set(true)
                },
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertNull(store.state.value.runtimeProgressMessage)
            assertTrue(attempted.get())
            assertEquals(store.text(R.string.priv_ui_start_failed), snackbar.await())
        }
    }

    @Test
    fun fallbackStartCanReplaceAnExistingConnection() = runBlocking {
        val attemptCount = AtomicInteger(0)
        val firstFailureEffectCalled = AtomicBoolean(false)
        RuntimeActionsFixture().use { (store, actions) ->
            store.connectAsShell()

            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                        onFailure = {
                            PrivilegeUiRuntimeStartFailureDisposition(
                                afterCommit = { firstFailureEffectCalled.set(true) },
                            )
                        },
                    ) {
                        attemptCount.incrementAndGet()
                        error("root unavailable")
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        attemptCount.incrementAndGet()
                        PrivilegeServerInfo(
                            uid = 0,
                            pid = 4321,
                            protocolVersion = 1,
                        )
                    },
                ),
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(0, store.state.value.serverInfo?.uid)
            assertNull(store.state.value.runtimeProgressMessage)
            assertEquals(2, attemptCount.get())
            assertTrue(firstFailureEffectCalled.get())
            assertTrue(store.state.value.startupLogLines.any { "root unavailable" in it })
        }
    }

    @Test
    fun fallbackStartAppliesSilentFailureEffectsBeforeContinuing() = runBlocking {
        val secondAttemptStarted = CountDownLatch(1)
        val firstFailureHandlerCalled = AtomicBoolean(false)
        val fallbackCleanupCalled = AtomicBoolean(false)
        val userActionCalled = AtomicBoolean(false)
        RuntimeActionsFixture().use { (store, actions) ->
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                        onFailure = {
                            firstFailureHandlerCalled.set(true)
                            PrivilegeUiRuntimeStartFailureDisposition(
                                stateTransform = { current ->
                                    current.copy(notificationPairingRunning = true)
                                },
                                snackbarMessage = "child failure",
                                startupLogLines = listOf("child prompt"),
                                afterCommit = { fallbackCleanupCalled.set(true) },
                                onUserActionRequired = { userActionCalled.set(true) },
                            )
                        },
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
            assertTrue(firstFailureHandlerCalled.get())
            assertTrue(fallbackCleanupCalled.get())
            assertFalse(userActionCalled.get())
            assertTrue(store.state.value.notificationPairingRunning)
            assertFalse(store.state.value.startupLogLines.contains("child prompt"))
            assertTrue(store.state.value.startupLogLines.any { "root unavailable" in it })
        }
    }

    @Test
    fun connectionClaimInterruptsCurrentAttemptAndPreventsFurtherFallback() = runBlocking {
        val attemptStarted = CountDownLatch(1)
        val releaseAttempt = CountDownLatch(1)
        val attemptExited = AtomicBoolean(false)
        val nextAttemptStarted = AtomicBoolean(false)
        RuntimeActionsFixture().use { (store, actions) ->
            try {
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        attemptStarted.countDown()
                        try {
                            releaseAttempt.await(30, TimeUnit.SECONDS)
                            error("blocked attempt should have been interrupted")
                        } finally {
                            attemptExited.set(true)
                        }
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        nextAttemptStarted.set(true)
                        shellServerInfo()
                    },
                ),
            )
                assertTrue(attemptStarted.await(2, TimeUnit.SECONDS))

                actions.connectForTest(shellServerInfo())

                assertTrue(waitUntilConnected(store))
                assertTrue(waitUntil { attemptExited.get() })
                assertFalse(nextAttemptStarted.get())
                assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, store.state.value.runtimeStartPhase)
            } finally {
                releaseAttempt.countDown()
            }
        }
    }

    @Test
    fun fallbackStopsAfterAdbCommandCompletedWithoutHandshake() = runBlocking {
        val nextAttemptStarted = AtomicBoolean(false)
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        throw PrivilegeServerLaunchUncertainException("handshake timed out")
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        nextAttemptStarted.set(true)
                        error("root should not start")
                    },
                ),
            )

            assertTrue(waitUntilIdle(store))
            assertFalse(nextAttemptStarted.get())
            assertEquals(PrivilegeUiRuntimeStatus.FAILED, store.state.value.runtimeStatus)
            assertEquals(store.text(R.string.priv_ui_start_failed), snackbar.await())
            assertTrue(store.state.value.startupLogLines.any { "handshake timed out" in it })
        }
    }

    @Test
    fun fallbackExternalRequestRemainsOwnedUntilConnectionOrTimeout() = runBlocking {
        val attemptOrder = mutableListOf<String>()
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 5_000L)
            },
        ).use { (store, actions) ->
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        synchronized(attemptOrder) { attemptOrder += "root" }
                        error("root unavailable")
                    },
                    PrivilegeUiRuntimeStartAttempt.Request(
                        message = "external",
                        startedMessage = "external requested",
                        startupSource = null,
                        runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                    ) {
                        synchronized(attemptOrder) { attemptOrder += "external" }
                    },
                ),
            )

            assertTrue(waitUntilRuntimeStartRequestSent(store, "external requested"))
            assertEquals(listOf("root", "external"), synchronized(attemptOrder) { attemptOrder.toList() })
            assertEquals(PrivilegeUiRuntimeStatus.STARTING, store.state.value.runtimeStatus)
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, store.state.value.runtimeStartPhase)
            assertTrue(store.state.value.busy)
            assertNotNull(store.runtimeStartSession)
            assertTrue(store.runtimeStartJob?.isActive == true)

            actions.connectForTest(shellServerInfo())

            assertTrue(waitUntilConnected(store))
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, store.state.value.runtimeStartPhase)
            assertNull(store.runtimeStartSession)
            assertNull(store.runtimeStartJob)
        }
    }

    @Test
    fun fallbackStopsAfterExternalRequestTimesOut() = runBlocking {
        val attemptOrder = mutableListOf<String>()
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 250L)
            },
        ).use { (store, actions) ->
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Request(
                        message = "external",
                        startedMessage = "external requested",
                        startupSource = null,
                        runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                    ) {
                        synchronized(attemptOrder) { attemptOrder += "external" }
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        synchronized(attemptOrder) { attemptOrder += "root" }
                        shellServerInfo()
                    },
                ),
            )

            assertTrue(waitUntilIdle(store))
            assertEquals(
                listOf("external"),
                synchronized(attemptOrder) { attemptOrder.toList() },
            )
            assertEquals(PrivilegeUiRuntimeStatus.FAILED, store.state.value.runtimeStatus)
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, store.state.value.runtimeStartPhase)
        }
    }

    @Test
    fun cancellationImmediatelyEntersCancellingAndSecondRequestHasNoSideEffect() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupCount = AtomicInteger(0)
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 250L)
            },
            beforeClose = release::countDown,
        ).use { (store, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    addCloseable(AutoCloseable { cleanupCount.incrementAndGet() })
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                },
            )

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, store.state.value.runtimeStartPhase)
            val session = store.runtimeStartSession
            val job = store.runtimeStartJob
            val generation = store.runtimeStartGeneration.get()

            actions.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, store.state.value.runtimeStartPhase)
            assertEquals(store.text(R.string.priv_ui_startup_cancelling), store.state.value.runtimeProgressMessage)
            assertTrue(waitUntil { cleanupCount.get() == 1 })

            actions.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, store.state.value.runtimeStartPhase)
            assertSame(session, store.runtimeStartSession)
            assertSame(job, store.runtimeStartJob)
            assertEquals(generation, store.runtimeStartGeneration.get())
            assertEquals(1, cleanupCount.get())

            release.countDown()
            assertTrue(waitUntilIdle(store))
        }
    }

    @Test
    fun externalZeroCancellationPointStaysCancellingUntilCallReturnsAndWaitTimesOut() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 350L)
            },
            beforeClose = release::countDown,
        ).use { (store, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    returned.countDown()
                },
            )

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()
            delay(50L)

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, store.state.value.runtimeStartPhase)
            assertEquals(1L, returned.count)

            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
        }
    }

    @Test
    fun cancellationKeepsStartPermitUntilExternalCallActuallyReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val permitHeld = AtomicBoolean(false)
        val acquirePermit = {
            if (!permitHeld.compareAndSet(false, true)) {
                null
            } else {
                AutoCloseable { permitHeld.set(false) }
            }
        }
        RuntimeActionsFixture(
            acquireStartPermit = acquirePermit,
            beforeClose = release::countDown,
        ).use { (store, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                },
            )

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()
            assertNull(acquirePermit())

            release.countDown()
            assertTrue(waitUntilIdle(store))
            assertTrue(waitUntil { !permitHeld.get() })
            val nextPermit = acquirePermit()
            assertNotNull(nextPermit)
            nextPermit!!.close()
        }
    }

    @Test
    fun cancellingFallbackDoesNotEnterNextAttempt() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val nextStarted = AtomicBoolean(false)
        RuntimeActionsFixture(
            beforeClose = releaseFirst::countDown,
        ).use { (store, actions) ->
            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "root",
                        startupSource = null,
                    ) {
                        firstStarted.countDown()
                        releaseFirst.await(30, TimeUnit.SECONDS)
                        error("cancelled attempt unexpectedly resumed")
                    },
                    PrivilegeUiRuntimeStartAttempt.Connect(
                        message = "adb",
                        startupSource = null,
                    ) {
                        nextStarted.set(true)
                        shellServerInfo()
                    },
                ),
            )

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()

            assertTrue(waitUntilIdle(store))
            assertFalse(nextStarted.get())
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
        }
    }

    @Test
    fun lateConnectionWhileCancellingWinsOverCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        RuntimeActionsFixture(
            configureStore = { store ->
                store.config = store.config.copy(startTimeoutMillis = 5_000L)
            },
            beforeClose = release::countDown,
        ).use { (store, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    returned.countDown()
                },
            )

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()
            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, store.state.value.runtimeStartPhase)

            actions.connectForTest(shellServerInfo())

            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, store.state.value.runtimeStartPhase)
            assertEquals(2000, store.state.value.serverInfo?.uid)
            assertNull(store.runtimeStartSession)
            assertNull(store.runtimeStartJob)

            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            delay(50L)
            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
        }
    }

    @Test
    fun connectedStateWaitsForCleanupAndUsesLatestHandshake() = runBlocking {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        RuntimeActionsFixture(
            beforeClose = releaseCleanup::countDown,
        ).use { (store, actions) ->
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "root",
                    startupSource = null,
                ) {
                    addCloseable(
                        AutoCloseable {
                            cleanupStarted.countDown()
                            releaseCleanup.await(30, TimeUnit.SECONDS)
                        },
                    )
                    shellServerInfo()
                },
            )

            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegeUiRuntimeStatus.STARTING, store.state.value.runtimeStatus)
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, store.state.value.runtimeStartPhase)
            assertNotNull(store.runtimeStartSession)

            actions.disconnectForTest()
            actions.connectForTest(
                PrivilegeServerInfo(
                    uid = 0,
                    pid = 5678,
                    protocolVersion = 1,
                ),
            )
            assertEquals(PrivilegeUiRuntimeStatus.STARTING, store.state.value.runtimeStatus)

            releaseCleanup.countDown()

            assertTrue(waitUntilConnected(store))
            assertEquals(0, store.state.value.serverInfo?.uid)
            assertNull(store.runtimeStartSession)
            assertEquals(store.text(R.string.priv_ui_connected), store.state.value.startupLogLines.last())
        }
    }

    @Test
    fun disconnectDuringConnectionCleanupDoesNotPublishStaleConnected() = runBlocking {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        RuntimeActionsFixture(
            beforeClose = releaseCleanup::countDown,
        ).use { (store, actions) ->
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "root",
                    startupSource = null,
                ) {
                    addCloseable(
                        AutoCloseable {
                            cleanupStarted.countDown()
                            releaseCleanup.await(30, TimeUnit.SECONDS)
                        },
                    )
                    shellServerInfo()
                },
            )

            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            actions.disconnectForTest()
            assertEquals(PrivilegeUiRuntimeStatus.STARTING, store.state.value.runtimeStatus)

            releaseCleanup.countDown()

            assertTrue(waitUntil { store.state.value.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE })
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertNull(store.state.value.serverInfo)
            assertNull(store.runtimeStartSession)
            assertNull(store.runtimeStartJob)
        }
    }

    @Test
    fun staleCancelCommandDoesNotStopConnectedServer() {
        RuntimeActionsFixture().use { (store, actions) ->
            store.connectAsShell()

            actions.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, store.state.value.runtimeStatus)
            assertEquals(2000, store.state.value.serverInfo?.uid)
        }
    }

    @Test
    fun handshakeClaimWinsWhileFailureCleanupIsFinishing() = runBlocking {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        RuntimeActionsFixture(
            beforeClose = releaseCleanup::countDown,
        ).use { (store, actions) ->
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "root",
                    startupSource = null,
                ) {
                    addCloseable(
                        AutoCloseable {
                            cleanupStarted.countDown()
                            releaseCleanup.await(30, TimeUnit.SECONDS)
                        },
                    )
                    error("command failed")
                },
            )
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))

            val lateConnection = async(Dispatchers.Default) {
                actions.connectForTest(shellServerInfo())
            }
            assertTrue(waitUntil { store.runtimeStartSession?.connectionClaimed == true })

            releaseCleanup.countDown()
            lateConnection.await()

            assertTrue(waitUntilConnected(store))
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, store.state.value.runtimeStartPhase)
            assertEquals(2000, store.state.value.serverInfo?.uid)
        }
    }

    @Test
    fun closeRunningStartCancelsResourcesOnceAndReturnsWithoutWaiting() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val cleanupCount = AtomicInteger(0)
        RuntimeActionsFixture(
            beforeClose = release::countDown,
        ).use { (store, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                ) {
                    addCloseable(AutoCloseable { cleanupCount.incrementAndGet() })
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    returned.countDown()
                },
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            actions.close()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("close blocked for ${elapsedMillis}ms", elapsedMillis < 500L)
            assertTrue(waitUntil { cleanupCount.get() == 1 })
            assertNull(store.runtimeStartSession)
            assertNull(store.runtimeStartJob)

            actions.close()
            assertEquals(1, cleanupCount.get())

            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun ownerCloseKeepsInteractivePermitUntilAsynchronousCleanupFinishes() = runBlocking {
        val entered = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val callReturned = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        RuntimeActionsFixture(
            acquireStartPermit = PrivilegeUiStartGate.newInteractivePermitAcquirer(),
            beforeClose = {
                releaseCall.countDown()
                releaseCleanup.countDown()
            },
        ).use { (_, actions) ->
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                ) {
                    addCloseable(
                        AutoCloseable {
                            cleanupStarted.countDown()
                            releaseCleanup.await(30, TimeUnit.SECONDS)
                        },
                    )
                    entered.countDown()
                    releaseCall.await(30, TimeUnit.SECONDS)
                    callReturned.countDown()
                },
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            actions.close()

            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            releaseCall.countDown()
            assertTrue(callReturned.await(2, TimeUnit.SECONDS))
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            releaseCleanup.countDown()
            assertTrue(waitUntil { PrivilegeUiStartGate.state.value.owner == null })
            PrivilegeUiStartGate.tryAcquireSilent()!!.close()
        }
    }

    @Test
    fun cancellationResourceFailureDoesNotSkipRemainingResources() {
        val session = PrivilegeUiRuntimeStartSession(generation = 1L)
        val closed = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        session.addCloseable(
            AutoCloseable {
                closed += "throwing"
                error("cleanup failed")
            },
        )
        session.addCloseable(AutoCloseable { closed += "second" })
        session.addCloseable(AutoCloseable { closed += "third" })

        assertTrue(session.markCancellationRequested())
        session.closeCancellationResources(failures::add)

        assertEquals(listOf("throwing", "second", "third"), closed)
        assertEquals(listOf("cleanup failed"), failures.map { it.message })
        assertFalse(session.markCancellationRequested())
        assertEquals(listOf("throwing", "second", "third"), closed)
    }

    @Test
    fun emptyFallbackReportsOnlyGenericStartFailure() = runBlocking {
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStartFallback(emptyList())

            assertEquals(store.text(R.string.priv_ui_start_failed), snackbar.await())
            assertEquals(
                store.text(R.string.priv_ui_start_failed),
                store.state.value.startupLogLines.last(),
            )
        }
    }

    @Test
    fun fallbackWorkflowRunsWithoutChildFeedback() = runBlocking {
        val workflowFeedbackEnabled = AtomicBoolean(true)
        RuntimeActionsFixture().use { (store, actions) ->
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) { waitForSnackbar(store) }

            actions.runServerStartFallback(
                listOf(
                    PrivilegeUiRuntimeStartAttempt.Workflow(
                        message = "adb",
                        startupSource = null,
                        runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                    ) {
                        workflowFeedbackEnabled.set(showAttemptFeedback)
                        if (showAttemptFeedback) {
                            store.showSnackbar("child failure")
                        }
                        PrivilegeUiRuntimeStartResult.Finished
                    },
                ),
            )

            assertTrue(waitUntilIdle(store))
            assertFalse(workflowFeedbackEnabled.get())
            assertEquals(store.text(R.string.priv_ui_start_failed), snackbar.await())
        }
    }

    @Test
    fun stopCurrentStartInterruptsRunningStartAndKeepsStoppedState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        RuntimeActionsFixture(context = context).use { (store, actions) ->
            actions.runServerStart(
                PrivilegeUiRuntimeStartAttempt.Connect(
                    message = "adb",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
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
            assertEquals(
                PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
                store.state.value.runtimeStartSource,
            )
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, store.state.value.runtimeStartPhase)
            actions.stopCurrentStart()

            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertNull(store.state.value.runtimeStartSource)
            assertNull(store.state.value.runtimeProgressMessage)
            assertEquals(
                context.getString(R.string.priv_ui_startup_interrupted),
                store.state.value.startupLogLines.last(),
            )
        }
    }

    @Test
    fun stopCurrentStartClosesRuntimeStartSessionResources() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        RuntimeActionsFixture(context = context).use { (store, actions) ->
            actions.runServerStartWorkflow(
                PrivilegeUiRuntimeStartAttempt.Workflow(
                    message = "adb",
                    startupSource = null,
                ) {
                    addCloseable(AutoCloseable { closed.countDown() })
                    started.countDown()
                    delay(TimeUnit.SECONDS.toMillis(30))
                    PrivilegeUiRuntimeStartResult.Finished
                },
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            actions.stopCurrentStart()

            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(waitUntilIdle(store))
            assertEquals(PrivilegeUiRuntimeStatus.DISCONNECTED, store.state.value.runtimeStatus)
            assertEquals(
                context.getString(R.string.priv_ui_startup_interrupted),
                store.state.value.startupLogLines.last(),
            )
        }
    }

    @Test
    fun cancellingStateRemainsOwnedUntilSlowCleanupFinishes() = runBlocking {
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        RuntimeActionsFixture(
            beforeClose = releaseCleanup::countDown,
        ).use { (store, actions) ->
            actions.runServerStartWorkflow(
                PrivilegeUiRuntimeStartAttempt.Workflow(
                    message = "adb",
                    startupSource = null,
                ) {
                    addCloseable(
                        AutoCloseable {
                            cleanupStarted.countDown()
                            releaseCleanup.await(30, TimeUnit.SECONDS)
                        },
                    )
                    started.countDown()
                    delay(TimeUnit.SECONDS.toMillis(30))
                    PrivilegeUiRuntimeStartResult.Finished
                },
            )
            assertTrue(started.await(2, TimeUnit.SECONDS))

            actions.stopCurrentStart()

            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, store.state.value.runtimeStartPhase)
            assertNotNull(store.runtimeStartSession)

            releaseCleanup.countDown()

            assertTrue(waitUntilIdle(store))
            assertNull(store.runtimeStartSession)
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
                !it.busy && it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE
            }
            true
        } ?: false

    private suspend fun waitUntil(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            while (!condition()) delay(10L)
            true
        } ?: false

    private suspend fun waitUntilRuntimeStartRequestSent(
        store: PrivilegeUiViewModelStore,
        message: String,
    ): Boolean =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            store.state.first {
                it.runtimeStatus == PrivilegeUiRuntimeStatus.STARTING &&
                    it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.RUNNING &&
                    it.runtimeProgressMessage == message
            }
            true
        } ?: false

    private suspend fun waitForSnackbar(store: PrivilegeUiViewModelStore): String? =
        withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
            store.snackbarMessages.first()
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
                runtimeProgressMessage = null,
            )
        }
    }

    private fun PrivilegeUiRuntimeActions.connectForTest(serverInfo: PrivilegeServerInfo) {
        val method = PrivilegeUiRuntimeActions::class.java.getDeclaredMethod(
            "connectServer",
            PrivilegeServerInfo::class.java,
        )
        method.isAccessible = true
        method.invoke(this, serverInfo)
    }

    private fun PrivilegeUiRuntimeActions.disconnectForTest() {
        val method = PrivilegeUiRuntimeActions::class.java.getDeclaredMethod("handleServerDisconnected")
        method.isAccessible = true
        method.invoke(this)
    }

    private fun PrivilegeUiRuntimeActions.updateDisconnectedIfIdleForTest(
        expectedConnectionSerial: Long,
    ) {
        val method = PrivilegeUiRuntimeActions::class.java.getDeclaredMethod(
            "updateDisconnectedIfIdle",
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, expectedConnectionSerial)
    }

    private fun shellServerInfo(): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = 2000,
            pid = 1234,
            protocolVersion = 1,
        )

    private class RuntimeActionsFixture(
        context: Context = RuntimeEnvironment.getApplication(),
        configureStore: (PrivilegeUiViewModelStore) -> Unit = {},
        shutdownServer: () -> Unit = { Privilege.shutdownServer() },
        isAdbPermissionRestricted: () -> Boolean = Privilege::isAdbPermissionRestricted,
        acquireStartPermit: () -> AutoCloseable? = { AutoCloseable {} },
        operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val beforeClose: () -> Unit = {},
    ) : AutoCloseable {
        val store = PrivilegeUiViewModelStore(context).apply(configureStore)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = PrivilegeUiRuntimeActions(
            store = store,
            coroutineScope = scope,
            shutdownServer = shutdownServer,
            isAdbPermissionRestricted = isAdbPermissionRestricted,
            acquireStartPermit = acquireStartPermit,
            operationDispatcher = operationDispatcher,
        )

        operator fun component1(): PrivilegeUiViewModelStore = store

        operator fun component2(): PrivilegeUiRuntimeActions = actions

        override fun close() {
            beforeClose()
            actions.close()
            scope.cancel()
            store.close()
        }
    }
}
