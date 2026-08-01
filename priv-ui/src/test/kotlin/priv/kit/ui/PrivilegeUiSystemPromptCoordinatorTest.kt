package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegeUiSystemPromptCoordinatorTest {
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

    private fun prompt(name: String): PrivilegeUiSystemPrompt =
        PrivilegeUiSystemPrompt(
            title = PrivilegeUiText.Literal("$name title"),
            message = PrivilegeUiText.Literal("$name message"),
        )

    private companion object {
        const val HOST_ID = "host"
        const val SECOND_HOST_ID = "second-host"
    }
}
