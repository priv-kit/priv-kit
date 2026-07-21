package priv.kit.ui

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.ui.runtime.PrivilegeUiDesiredEnabledStore
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.runtime.PrivilegeUiStartMethod
import priv.kit.ui.runtime.PrivilegeUiStartMethodStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiSilentStartTest {
    private val application = RuntimeEnvironment.getApplication()
    private val methodFile = File(application.filesDir, ".priv-kit/ui-start-method")
    private val temporaryFile = File(application.filesDir, ".priv-kit/ui-start-method.tmp")
    private val desiredFile = File(application.filesDir, ".priv-kit/ui-desired-enabled")
    private val desiredTemporaryFile = File(application.filesDir, ".priv-kit/ui-desired-enabled.tmp")

    @Before
    fun setUp() {
        methodFile.delete()
        temporaryFile.delete()
        desiredFile.delete()
        desiredTemporaryFile.delete()
    }

    @After
    fun tearDown() {
        methodFile.delete()
        temporaryFile.delete()
        desiredFile.delete()
        desiredTemporaryFile.delete()
    }

    @Test
    fun missingHistoryReturnsNullWithoutInitializingUi() = runBlocking {
        assertNull(
            PrivilegeUi.startSilently(
                context = application,
                config = PrivilegeUiConfig(),
            ),
        )
    }

    @Test
    fun disabledDesiredStateSkipsSilentGate() = runBlocking {
        PrivilegeUiDesiredEnabledStore(application).write(false)
        val initialCompletionSerial = PrivilegeUiStartGate.state.value.silentCompletionSerial

        assertNull(
            PrivilegeUi.startSilentlyIfEnabled(
                context = application,
                config = PrivilegeUiConfig(),
            ),
        )

        assertEquals(
            initialCompletionSerial,
            PrivilegeUiStartGate.state.value.silentCompletionSerial,
        )
    }

    @Test
    fun enabledDesiredStateDelegatesToSilentGate() = runBlocking {
        PrivilegeUiDesiredEnabledStore(application).write(true)
        val initialCompletionSerial = PrivilegeUiStartGate.state.value.silentCompletionSerial

        assertNull(
            PrivilegeUi.startSilentlyIfEnabled(
                context = application,
                config = PrivilegeUiConfig(),
            ),
        )

        assertEquals(
            initialCompletionSerial + 1L,
            PrivilegeUiStartGate.state.value.silentCompletionSerial,
        )
    }

    @Test
    fun skippedSilentStartDoesNotOverwriteSavedMethod() = runBlocking {
        val store = PrivilegeUiStartMethodStore(application)
        store.write(PrivilegeUiStartMethod.Root)

        val result = PrivilegeUi.startSilently(
            context = application,
            config = PrivilegeUiConfig(startupModes = setOf(PrivilegeUiStartupMode.ADB)),
        )

        assertNull(result)
        assertEquals(PrivilegeUiStartMethod.Root, store.read())
    }

    @Test
    fun interactiveOwnerPreventsSilentReplayWithoutChangingHistory() = runBlocking {
        val store = PrivilegeUiStartMethodStore(application)
        store.write(PrivilegeUiStartMethod.Root)
        val interactivePermit =
            PrivilegeUiStartGate.newInteractivePermitAcquirer().invoke()!!
        try {
            assertNull(
                PrivilegeUi.startSilently(
                    context = application,
                    config = PrivilegeUiConfig(),
                ),
            )
            assertEquals(PrivilegeUiStartMethod.Root, store.read())
        } finally {
            interactivePermit.close()
        }
    }
}
