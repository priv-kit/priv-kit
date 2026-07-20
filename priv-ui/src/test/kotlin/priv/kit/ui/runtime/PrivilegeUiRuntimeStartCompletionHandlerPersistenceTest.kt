package priv.kit.ui.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionEvent
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionOrigin
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiRuntimeStartCompletionHandlerPersistenceTest {
    private val serverInfo = PrivilegeServerInfo(uid = 2000, pid = 42, protocolVersion = 1)

    @Test
    fun connectedInteractiveSessionRecordsWinningMethod() {
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
            providerId = null,
        )

        fixture.completeConnected(serverInfo)

        assertEquals(listOf(PrivilegeUiStartMethod.AdbTcpip), fixture.recordedMethods)
        assertSame(serverInfo, fixture.publishedServer)
    }

    @Test
    fun connectedExternalSessionRecordsExactProviderId() {
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.EXTERNAL,
            providerId = "provider:child",
        )

        fixture.completeConnected(serverInfo)

        assertEquals(
            listOf(PrivilegeUiStartMethod.External("provider:child")),
            fixture.recordedMethods,
        )
    }

    @Test
    fun spontaneousOrNonRecordingConnectionDoesNotWriteMethod() {
        val spontaneous = fixture(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
            installSession = false,
        )
        spontaneous.handler.handleServerConnected(
            PrivilegeRuntimeConnectionEvent(
                serverInfo = serverInfo,
                origin = PrivilegeRuntimeConnectionOrigin.OWNER_RECONNECT,
                clientStartOperationId = null,
                initialLaunchId = null,
            ),
        )
        assertTrue(spontaneous.recordedMethods.isEmpty())

        val nonRecording = fixture(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
            recordSuccessfulMethod = false,
        )
        nonRecording.completeConnected(serverInfo)
        assertTrue(nonRecording.recordedMethods.isEmpty())
    }

    @Test
    fun committedMethodDoesNotFollowLaterUiStateChanges() {
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
        )
        fixture.store.updateState {
            it.copy(
                runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                runtimeStartProviderId = "other-provider",
            )
        }

        fixture.completeConnected(serverInfo)

        assertEquals(listOf(PrivilegeUiStartMethod.Root), fixture.recordedMethods)
    }

    @Test
    fun cancellationBeforeConnectionPublishesWithoutRecordingMethod() {
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
        )
        fixture.session.markCancellationRequested()

        fixture.completeConnected(serverInfo)

        assertTrue(fixture.recordedMethods.isEmpty())
        assertSame(serverInfo, fixture.publishedServer)
    }

    @Test
    fun persistenceFailureDoesNotPreventPublishingConnectedServer() {
        val permitCloseCount = AtomicInteger(0)
        val permitLease = PrivilegeUiStartPermitLease(
            AutoCloseable { permitCloseCount.incrementAndGet() },
        )
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.ROOT,
            providerId = null,
            onConnectionClaimed = permitLease::markConnectionClaimed,
            onConnectionHandled = permitLease::markConnectionHandled,
            writeMethod = { throw IllegalStateException("disk unavailable") },
        )

        fixture.completeConnected(serverInfo)
        permitLease.markJobCompleted(noConnectionCanBeClaimed = true)

        assertSame(serverInfo, fixture.publishedServer)
        assertEquals(1, permitCloseCount.get())
    }

    @Test
    fun startPermitWaitsForJobCompletionAndBlockedPersistence() {
        val writeStarted = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val permitCloseCount = AtomicInteger(0)
        val permitLease = PrivilegeUiStartPermitLease(
            AutoCloseable { permitCloseCount.incrementAndGet() },
        )
        val fixture = fixture(
            source = PrivilegeUiRuntimeStartSource.EXTERNAL,
            providerId = "provider",
            onConnectionClaimed = permitLease::markConnectionClaimed,
            onConnectionHandled = permitLease::markConnectionHandled,
            writeMethod = {
                writeStarted.countDown()
                allowWrite.await(30, TimeUnit.SECONDS)
            },
        )
        val connectionThread = thread(start = true) {
            fixture.completeConnected(serverInfo)
        }
        try {
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            permitLease.markJobCompleted(noConnectionCanBeClaimed = true)

            assertEquals(0, permitCloseCount.get())
            assertEquals(null, fixture.publishedServer)

            allowWrite.countDown()
            connectionThread.join(TimeUnit.SECONDS.toMillis(2))
            assertTrue(!connectionThread.isAlive)
            assertEquals(1, permitCloseCount.get())
        } finally {
            allowWrite.countDown()
            connectionThread.join(TimeUnit.SECONDS.toMillis(2))
        }
    }

    private fun fixture(
        source: PrivilegeUiRuntimeStartSource,
        providerId: String?,
        installSession: Boolean = true,
        recordSuccessfulMethod: Boolean = true,
        onConnectionClaimed: () -> Unit = {},
        onConnectionHandled: () -> Unit = {},
        writeMethod: (PrivilegeUiStartMethod) -> Unit = {},
    ): Fixture {
        val store = PrivilegeUiViewModelStore()
        val generation = 1L
        val session = PrivilegeUiRuntimeStartSession(
            generation = generation,
            recordSuccessfulMethod = recordSuccessfulMethod,
            onConnectionClaimed = onConnectionClaimed,
            onConnectionHandled = onConnectionHandled,
        )
        if (installSession) {
            val runtimeStartLease = checkNotNull(
                PrivilegeRuntimeStartCoordinator.tryCommitClientStart(
                    PrivilegeRuntimeStartCoordinator.beginPreflight(),
                ),
            )
            check(session.attachRuntimeStartLease(runtimeStartLease))
            session.commitStartMethod(privilegeUiStartMethod(source, providerId))
            session.markRuntimeStartJobCompleted()
        }
        store.runtimeStartGeneration.set(generation)
        if (installSession) store.runtimeStartSession = session
        store.updateState {
            it.copy(
                runtimeStartPhase = if (installSession) {
                    PrivilegeUiRuntimeStartPhase.RUNNING
                } else {
                    PrivilegeUiRuntimeStartPhase.IDLE
                },
                runtimeStartSource = source,
                runtimeStartProviderId = providerId,
            )
        }
        val recorded = mutableListOf<PrivilegeUiStartMethod>()
        val fixture = Fixture(
            store = store,
            session = session,
            recordedMethods = recorded,
        )
        fixture.handler = PrivilegeUiRuntimeStartCompletionHandler(
            store = store,
            isClosed = { false },
            isCurrentRuntimeStartLocked = { candidate ->
                store.runtimeStartSession === candidate &&
                    store.runtimeStartGeneration.get() == candidate.generation &&
                    !candidate.finished &&
                    !candidate.connectionClaimed
            },
            ownsRuntimeStartLocked = { candidate ->
                store.runtimeStartSession === candidate &&
                    store.runtimeStartGeneration.get() == candidate.generation
            },
            publishConnectedServer = { connected -> fixture.publishedServer = connected },
            recordSuccessfulStartMethod = { method ->
                writeMethod(method)
                recorded += method
            },
        )
        return fixture
    }

    private class Fixture(
        val store: PrivilegeUiViewModelStore,
        val session: PrivilegeUiRuntimeStartSession,
        val recordedMethods: MutableList<PrivilegeUiStartMethod>,
    ) {
        lateinit var handler: PrivilegeUiRuntimeStartCompletionHandler
        var publishedServer: PrivilegeServerInfo? = null

        fun completeConnected(serverInfo: PrivilegeServerInfo) {
            handler.completeRuntimeStart(
                session = session,
                completion = RuntimeStartCompletion.Connected(serverInfo),
            )
        }
    }
}
