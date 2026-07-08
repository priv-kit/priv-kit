package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiStatusRefreshControllerTest {
    @Test
    fun startExposesLoadingAndJoinWaitsForCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PrivilegeUiStatusRefreshController(
            scope = scope,
            name = "test-status-refresh",
        )
        val release = CountDownLatch(1)
        var duplicateRan = false

        try {
            assertTrue(
                controller.start {
                    release.await(2, TimeUnit.SECONDS)
                },
            )
            assertTrue(controller.loading)
            assertEquals(PrivilegeUiStatusRefreshState.RUNNING, controller.state.value)
            assertFalse(
                controller.start {
                    duplicateRan = true
                },
            )

            release.countDown()

            assertTrue(controller.join(2_000L))
            assertFalse(controller.loading)
            assertEquals(PrivilegeUiStatusRefreshState.IDLE, controller.state.value)
            assertFalse(duplicateRan)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun runUsesTheSameLoadingGate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PrivilegeUiStatusRefreshController(
            scope = scope,
            name = "test-status-refresh",
        )
        var observedLoading = false

        try {
            assertTrue(
                controller.run {
                    observedLoading = controller.loading
                },
            )

            assertTrue(observedLoading)
            assertFalse(controller.loading)
            assertEquals(PrivilegeUiStatusRefreshState.IDLE, controller.state.value)
        } finally {
            scope.cancel()
        }
    }
}
