package priv.kit.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.ui.adb.PrivilegeUiStaticTcpConfirmationController
import priv.kit.ui.adb.PrivilegeUiStaticTcpSwitchAction

class PrivilegeUiAdbTcpConfirmationTest {
    @Test
    fun confirmationResumesTheSuspendedRequest() = runBlocking {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.START_SERVICE)
        }

        assertEquals(
            PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            controller.pendingAction.value,
        )

        controller.confirm()

        assertTrue(result.await())
        assertNull(controller.pendingAction.value)
    }

    @Test
    fun cancellingResumesTheSuspendedRequestWithoutApproval() = runBlocking {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT)
        }

        controller.cancel()

        assertFalse(result.await())
        assertNull(controller.pendingAction.value)
    }

    @Test
    fun serviceStartReplacesAnExistingEnablePortDecision() = runBlocking {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.START_SERVICE)
        }

        assertFalse(first.await())
        assertEquals(
            PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            controller.pendingAction.value,
        )

        controller.confirm()
        assertTrue(second.await())
    }

    @Test
    fun enablePortCannotReplaceAnExistingServiceStartDecision() = runBlocking {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.START_SERVICE)
        }

        assertFalse(
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT),
        )
        assertEquals(
            PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            controller.pendingAction.value,
        )

        controller.confirm()
        assertTrue(first.await())
    }

    @Test
    fun cancellingTheWaitingCoroutineClearsTheDialog() = runBlocking {
        val controller = PrivilegeUiStaticTcpConfirmationController()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            controller.awaitConfirmation(PrivilegeUiStaticTcpSwitchAction.START_SERVICE)
        }

        request.cancelAndJoin()

        assertNull(controller.pendingAction.value)
    }
}
