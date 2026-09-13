package priv.kit.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeUiSystemPromptCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun permissionPromptDisplayIsDebounced() = runTest {
        val source = MutableStateFlow<PrivilegeUiVisibleSystemPrompt?>(null)
        val observed = mutableListOf<PrivilegeUiVisibleSystemPrompt?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.debouncedForDisplay().collect(observed::add)
        }
        val expected = visiblePrompt("notification", displayDelayMillis = 500)

        source.value = expected
        runCurrent()
        assertNull(observed.last())

        advanceTimeBy(499)
        runCurrent()
        assertNull(observed.last())

        source.value = null
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertNull(observed.last())

        source.value = expected
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(expected, observed.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun promptWithoutDisplayDelayAppearsImmediately() = runTest {
        val source = MutableStateFlow<PrivilegeUiVisibleSystemPrompt?>(null)
        val observed = mutableListOf<PrivilegeUiVisibleSystemPrompt?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.debouncedForDisplay().collect(observed::add)
        }
        val expected = visiblePrompt("tcp", displayDelayMillis = 0)

        source.value = expected
        runCurrent()

        assertEquals(expected, observed.last())
    }

    @Test
    fun armedPromptDoesNotAppearWithoutForegroundLoss() {
        val coordinator = coordinatorWithForegroundHost()
        val session = coordinator.begin(prompt("notification"))

        assertNull(coordinator.visiblePrompt.value)

        session.close()
        coordinator.onHostPaused(HOST_ID)

        assertNull(coordinator.visiblePrompt.value)
        coordinator.close()
    }

    @Test
    fun firstWindowFocusLossShowsPromptUntilOperationCompletes() {
        val coordinator = coordinatorWithForegroundHost()
        val expected = prompt("tcp")
        val session = coordinator.begin(expected)

        coordinator.onHostWindowFocusChanged(
            hostId = HOST_ID,
            hasWindowFocus = false,
            resumed = true,
        )

        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)
        session.close()
        assertNull(coordinator.visiblePrompt.value)
        coordinator.close()
    }

    @Test
    fun resumedWithoutWindowFocusKeepsVisiblePrompt() {
        val coordinator = coordinatorWithForegroundHost()
        val expected = prompt("wireless")
        coordinator.begin(expected)

        coordinator.onHostPaused(HOST_ID)
        coordinator.onHostResumed(HOST_ID, hasWindowFocus = false)

        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)

        coordinator.onHostWindowFocusChanged(
            hostId = HOST_ID,
            hasWindowFocus = true,
            resumed = true,
        )

        assertNull(coordinator.visiblePrompt.value)
        coordinator.close()
    }

    @Test
    fun completedPromptDismissesOnResumeEvenWhenAppDialogKeepsWindowFocus() {
        val coordinator = coordinatorWithForegroundHost()
        val expected = prompt("notification")
        val session = coordinator.begin(expected)

        coordinator.onHostPaused(HOST_ID)
        session.close()
        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)

        coordinator.onHostResumed(HOST_ID, hasWindowFocus = false)

        assertNull(coordinator.visiblePrompt.value)
        coordinator.close()
    }

    @Test
    fun onlyOwningHostCanRevealOrDismissPrompt() {
        val coordinator = coordinatorWithForegroundHost()
        coordinator.registerHost(
            hostId = SECOND_HOST_ID,
            resumed = true,
            hasWindowFocus = true,
        )
        val expected = prompt("external")
        coordinator.begin(expected, ownerHostId = HOST_ID)

        coordinator.onHostPaused(SECOND_HOST_ID)
        assertNull(coordinator.visiblePrompt.value)

        coordinator.onHostPaused(HOST_ID)
        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)
        assertEquals(HOST_ID, coordinator.visiblePrompt.value?.ownerHostId)

        coordinator.onHostResumed(SECOND_HOST_ID, hasWindowFocus = true)
        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)

        coordinator.onHostResumed(HOST_ID, hasWindowFocus = true)
        assertNull(coordinator.visiblePrompt.value)
        coordinator.close()
    }

    @Test
    fun staleSessionCannotClearNextPrompt() {
        val coordinator = coordinatorWithForegroundHost()
        val firstSession = coordinator.begin(prompt("first"))
        coordinator.onHostPaused(HOST_ID)
        coordinator.onHostResumed(HOST_ID, hasWindowFocus = true)

        val expected = prompt("second")
        coordinator.begin(expected)
        firstSession.close()
        coordinator.onHostPaused(HOST_ID)

        assertEquals(expected, coordinator.visiblePrompt.value?.prompt)
        coordinator.close()
    }

    private fun coordinatorWithForegroundHost(): PrivilegeUiSystemPromptCoordinator =
        PrivilegeUiSystemPromptCoordinator().also { coordinator ->
            coordinator.registerHost(
                hostId = HOST_ID,
                resumed = true,
                hasWindowFocus = true,
            )
        }

    private fun prompt(
        name: String,
        displayDelayMillis: Long = 0,
    ): PrivilegeUiSystemPrompt =
        PrivilegeUiSystemPrompt(
            title = PrivilegeUiText.Literal("$name title"),
            message = PrivilegeUiText.Literal("$name message"),
            displayDelayMillis = displayDelayMillis,
        )

    private fun visiblePrompt(
        name: String,
        displayDelayMillis: Long,
    ): PrivilegeUiVisibleSystemPrompt = PrivilegeUiVisibleSystemPrompt(
        ownerHostId = HOST_ID,
        prompt = prompt(name, displayDelayMillis),
    )

    private companion object {
        const val HOST_ID = "host"
        const val SECOND_HOST_ID = "second-host"
    }
}
