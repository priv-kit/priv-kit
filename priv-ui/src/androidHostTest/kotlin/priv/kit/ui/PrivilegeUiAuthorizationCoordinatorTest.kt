package priv.kit.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeUiAuthorizationCoordinatorTest {
    @Test
    fun authorizationDoesNotRequireAVisibleHostAndEndsWithOperation() = runTest {
        val coordinator = PrivilegeUiAuthorizationCoordinator()
        val result = CompletableDeferred<Unit>()
        val operation = launch {
            coordinator.withAuthorization(privilegeUiRootAuthorization()) { result.await() }
        }
        runCurrent()
        assertEquals(PrivilegeUiAuthorizationKind.ROOT, coordinator.authorization.value?.kind)
        result.complete(Unit)
        operation.join()
        assertNull(coordinator.authorization.value)
    }

    @Test
    fun cancellationClearsAuthorization() = runTest {
        val coordinator = PrivilegeUiAuthorizationCoordinator()
        val operation = launch {
            coordinator.withAuthorization(privilegeUiTcpAuthorization()) { awaitCancellation() }
        }
        runCurrent()
        operation.cancelAndJoin()
        assertNull(coordinator.authorization.value)
    }
}
