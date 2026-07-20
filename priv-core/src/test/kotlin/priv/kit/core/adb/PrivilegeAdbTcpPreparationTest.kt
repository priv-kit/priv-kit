package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbTcpPreparationTest {
    @Test
    fun availableEndpointIsReturnedWithoutPreparingAdb() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.AUTHORIZED)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableEndpointWithoutPermissionReturnsImmediately() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = false,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableUnconfiguredEndpointDoesNotEnableAdb() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = null,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableDifferentConfiguredEndpointDoesNotEnableAdb() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT + 1,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun manageableEndpointRetriesUntilAdbStartsListening() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        val checks = ArrayDeque(
            listOf(
                authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE),
                authorizationResult(PrivilegeAdbAuthorizationStatus.AUTHORIZED),
            ),
        )
        var enableCalls = 0
        var sleepCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            retryCount = 5,
            retryDelayMillis = 10L,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { checks.removeFirst() },
            sleep = { delayMillis ->
                assertEquals(10L, delayMillis)
                sleepCalls += 1
            },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, result.status)
        assertEquals(1, enableCalls)
        assertEquals(2, sleepCalls)
    }

    @Test
    fun unauthorizedEndpointStopsRecoveryForExistingAuthorizationFlow() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        var checkCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            retryCount = 5,
            retryDelayMillis = 0L,
            enableAdb = {},
            checkAuthorization = {
                checkCalls += 1
                authorizationResult(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED)
            },
            sleep = { error("zero-delay recovery should not sleep") },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED, result.status)
        assertEquals(1, checkCalls)
    }

    @Test
    fun exhaustedRecoveryReturnsLastUnavailableResult() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)
        var checkCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            retryCount = 3,
            retryDelayMillis = 0L,
            enableAdb = {},
            checkAuthorization = {
                checkCalls += 1
                authorizationResult(
                    status = PrivilegeAdbAuthorizationStatus.UNAVAILABLE,
                    failureMessage = "attempt $checkCalls",
                )
            },
            sleep = { error("zero-delay recovery should not sleep") },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, result.status)
        assertEquals("attempt 3", result.failureMessage)
        assertEquals(3, checkCalls)
    }

    @Test
    fun preparationFailureStaysUnavailableAndAddsDiagnostic() {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE)

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            enableAdb = { error("denied") },
            checkAuthorization = { error("authorization should not be checked after preparation failure") },
            sleep = { error("recovery should not wait after preparation failure") },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, result.status)
        assertTrue(result.failureMessage.orEmpty().contains("denied"))
        assertTrue(result.outputText.contains("Failed to prepare ADB for static TCP"))
    }

    private fun authorizationResult(
        status: PrivilegeAdbAuthorizationStatus,
        failureMessage: String? = null,
    ): PrivilegeAdbAuthorizationCheckResult =
        PrivilegeAdbAuthorizationCheckResult(
            status = status,
            outputText = "[diag] test",
            identity = PrivilegeAdbIdentity.default(),
            failureMessage = failureMessage,
        )

    private companion object {
        private const val TEST_TCP_PORT = 5555
    }
}
