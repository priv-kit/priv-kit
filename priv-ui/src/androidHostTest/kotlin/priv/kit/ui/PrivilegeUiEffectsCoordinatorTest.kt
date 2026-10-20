package priv.kit.ui

import android.content.ClipboardManager
import java.io.File
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import priv.kit.core.Privilege
import priv.kit.core.internal.runtime.PrivilegeContext
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import priv.kit.ui.adb.PrivilegeUiAdbActions
import priv.kit.ui.external.PrivilegeUiExternalStartActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.copyManualShellCommand
import priv.kit.ui.state.PrivilegeUiViewModelStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = PrivilegeUiExternalShellScriptTest.RecordingApplication::class)
class PrivilegeUiEffectsCoordinatorTest {
    @Test
    fun manualCommandIsPreparedOnceAcrossSilentStartRefreshAndPageChanges() = runTest {
        ShadowProcess.setUid(10_000)
        val app = RuntimeEnvironment.getApplication() as PrivilegeUiExternalShellScriptTest.RecordingApplication
        app.applicationInfo.apply {
            sourceDir = "/data/app/example/base.apk"
            splitSourceDirs = null
            nativeLibraryDir = File(app.cacheDir, "native/arm64").apply {
                mkdirs()
                File(this, "libprivkitstarter.so").writeBytes(byteArrayOf(1))
            }.path.replace('\\', '/')
        }
        app.externalDirectory = File(app.cacheDir, "external/files")
        PrivilegeContext.install(app)
        val fixture = Fixture(this, listOf(PrivilegeUiStartupMode.ROOT, PrivilegeUiStartupMode.MANUAL_SHELL),
            manualShellScriptPath = "files/start.sh")
        val command = "adb shell sh /sdcard/Android/data/${app.packageName}/files/start.sh"
        try {
            assertEquals(command, fixture.store.state.value.manualShellCommandLine)
            fixture.store.copyManualShellCommand()
            assertEquals(command, app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            // The loader publishes this value after its IO block has completed.
            fixture.store.updateState { it.copy(manualShellCommandLine = null) }
            checkNotNull(PrivilegeUiStartGate.tryAcquireSilent()).use {
                fixture.effects.initialize()
                testScheduler.runCurrent()
                assertFalse(fixture.effects.canInteract())
                fixture.store.state.first { it.manualShellCommandLine == command }
                testScheduler.runCurrent()
                assertEquals("#!/system/bin/sh\n${Privilege.nativeStarterCommand}\n",
                    File(app.externalDirectory, "start.sh").readText())
            }
            testScheduler.runCurrent()
            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            fixture.effects.refreshHostResumeState()
            testScheduler.runCurrent()
            fixture.store.updateState { it.copy(selectedStartupMode = PrivilegeUiStartupMode.MANUAL_SHELL) }
            testScheduler.runCurrent()
            fixture.effects.setHostResumed("a", false)
            testScheduler.runCurrent()
            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
        } finally {
            fixture.close()
            // Closing cancels long-lived effects; joining also waits for running IO to finish.
            backgroundScope.coroutineContext.job.children.toList().joinAll()
        }
        assertEquals(1, app.externalFilesCalls)
        assertEquals(command, fixture.store.state.value.manualShellCommandLine)
    }

    @Test
    fun missingPermissionStopsWirelessRefreshAndPollingUntilGranted() = runTest {
        val fixture = Fixture(this)
        fun resetStatus(missing: Boolean) = fixture.store.updateState {
            it.copy(selectedStartupMode = PrivilegeUiStartupMode.ADB, localNetworkPermissionMissing = missing,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN)
        }
        fun poll() {
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
        }
        try {
            resetStatus(true)
            fixture.effects.setHostResumed("a", true)
            fixture.effects.initialize()
            poll()
            assertEquals(PrivilegeUiWirelessAdbStatus.UNKNOWN, fixture.store.state.value.wirelessDebuggingStatus)
            resetStatus(false)
            poll()
            assertEquals(PrivilegeUiWirelessAdbStatus.OFF, fixture.store.state.value.wirelessDebuggingStatus)
            // Reset only the observed value: the next polling tick must refresh it again.
            resetStatus(false)
            poll()
            assertEquals(PrivilegeUiWirelessAdbStatus.OFF, fixture.store.state.value.wirelessDebuggingStatus)
            resetStatus(true)
            fixture.effects.refreshHostResumeState()
            poll()
            assertEquals(PrivilegeUiWirelessAdbStatus.UNKNOWN, fixture.store.state.value.wirelessDebuggingStatus)
            resetStatus(false)
            poll()
            assertEquals(PrivilegeUiWirelessAdbStatus.OFF, fixture.store.state.value.wirelessDebuggingStatus)
        } finally { fixture.close() }
    }

    @Test
    fun onlyVisibleSelectedPagePollsAndLastHostPauseStopsIt() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.effects.initialize()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(0, fixture.provider.calls)

            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.calls)
            fixture.effects.setHostResumed("b", true)
            fixture.effects.setHostResumed("a", false)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)

            fixture.effects.setHostResumed("b", false)
            testScheduler.runCurrent()
            fixture.effects.refreshHostResumeState()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)

            fixture.effects.setHostResumed("b", true)
            testScheduler.runCurrent()
            assertEquals(3, fixture.provider.calls)
            fixture.store.updateState { it.copy(selectedStartupMode = PrivilegeUiStartupMode.ROOT) }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(3, fixture.provider.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingPageCancelsActiveRefreshAndResumeStartsFresh() = runTest {
        val fixture = Fixture(this)
        fixture.provider.blocker = CompletableDeferred()
        try {
            fixture.effects.setHostResumed("a", true)
            fixture.effects.initialize()
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.calls)
            fixture.effects.removeHost("a")
            testScheduler.runCurrent()
            assertEquals(1, fixture.provider.cancelled)
            fixture.provider.blocker = null
            fixture.effects.setHostResumed("a", true)
            testScheduler.runCurrent()
            assertEquals(2, fixture.provider.calls)
        } finally {
            fixture.close()
        }
    }

    private class Provider : PrivilegeUiExternalStartProvider {
        override val id = "test"
        override val label = "Test"
        var calls = 0
        var cancelled = 0
        var blocker: CompletableDeferred<Unit>? = null
        override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            calls++
            try {
                blocker?.await()
            } catch (exception: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw exception
            }
            return PrivilegeUiExternalStartSnapshot(available = true, authorized = true)
        }
        override suspend fun start(context: Context, commandLine: String) = Unit
    }

    private class Fixture(
        scope: TestScope,
        startupModes: List<PrivilegeUiStartupMode> = listOf(PrivilegeUiStartupMode.EXTERNAL, PrivilegeUiStartupMode.ROOT),
        manualShellScriptPath: String? = null,
    ) : AutoCloseable {
        val provider = Provider()
        val store = PrivilegeUiViewModelStore(
            RuntimeEnvironment.getApplication(),
            PrivilegeUiConfig(
                startupModes = startupModes,
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(provider),
                externalStartStatusPollIntervalMillis = 100,
                wirelessStatusPollIntervalMillis = 100,
                manualShellScriptPath = manualShellScriptPath,
            ),
        )
        private val owner = PrivilegeUiStartGate.newInteractiveOwner()
        private val prompts = PrivilegeUiSystemPromptCoordinator()
        private val runtime = PrivilegeUiRuntimeActions(
            store, scope.backgroundScope, owner::tryAcquire, prompts,
            operationDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        private val adb = PrivilegeUiAdbActions(
            store, runtime, scope.backgroundScope, owner::tryAcquire, { false }, prompts,
        )
        private val external = PrivilegeUiExternalStartActions(
            store, runtime, acquireInteractivePermit = owner::tryAcquire, systemPromptCoordinator = prompts,
        )
        val effects = PrivilegeUiEffectsCoordinator(
            store, owner, runtime, adb, external, scope.backgroundScope,
        )
        override fun close() {
            effects.close()
            adb.close()
            runtime.close()
            prompts.close()
        }
    }
}
