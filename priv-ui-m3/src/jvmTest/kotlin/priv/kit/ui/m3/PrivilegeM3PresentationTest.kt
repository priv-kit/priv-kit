package priv.kit.ui.m3

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import priv.kit.ui.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeM3PresentationTest {
    @Test
    fun explanationWaitsForUiAndCancellationClearsIt() = runTest {
        val model = PrivilegeM3Controller(this)
        val waiting = async { model.explainAccess(PrivilegeM3AccessExplanation.LOCAL_NETWORK) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(PrivilegeM3AccessExplanation.LOCAL_NETWORK, model.state.accessExplanation)
        model.actions.answerAccessExplanation(true)
        assertTrue(waiting.await())
        assertNull(model.state.accessExplanation)
        val cancelled = launch { model.explainAccess(PrivilegeM3AccessExplanation.NOTIFICATIONS) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertNull(model.state.accessExplanation)
    }

    @Test
    fun presentationDelayAndVisibilityDoNotChangeBusinessAuthorization() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = PrivilegeM3Controller(this)
            val authorization = PrivilegeUiAuthorization(PrivilegeUiAuthorizationKind.ROOT)
            model.updateAuthorization(authorization)
            assertNull(model.state.visibleAuthorization)
            model.updateHostVisibility(resumed = true, focused = false)
            advanceTimeBy(499)
            assertNull(model.state.visibleAuthorization)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(authorization, model.state.visibleAuthorization)
            model.updateHostVisibility(resumed = true, focused = true)
            assertNull(model.state.visibleAuthorization)
            model.updateHostVisibility(resumed = false, focused = false)
            model.updateAuthorization(null)
            advanceUntilIdle()
            assertNull(model.state.visibleAuthorization)
        } finally { Dispatchers.resetMain() }
    }
}
