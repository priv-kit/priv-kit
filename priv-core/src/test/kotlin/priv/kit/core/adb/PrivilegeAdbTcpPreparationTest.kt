package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PrivilegeAdbTcpPreparationTest {
    @Test
    fun availableEndpointIsReturnedWithoutPreparingAdb() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.AUTHORIZED, null)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
            retryCount = TEST_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableEndpointWithoutPermissionReturnsImmediately() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = false,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
            retryCount = TEST_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableUnconfiguredEndpointDoesNotEnableAdb() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = null,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
            retryCount = TEST_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun unavailableDifferentConfiguredEndpointDoesNotEnableAdb() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
        var enableCalls = 0

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT + 1,
            canManageAdb = true,
            enableAdb = { enableCalls += 1 },
            checkAuthorization = { error("authorization should not be checked again") },
            sleep = { error("recovery should not wait") },
            retryCount = TEST_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )

        assertSame(initial, result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun manageableEndpointRetriesUntilAdbStartsListening() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
        val checks = ArrayDeque(
            listOf(
                authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null),
                authorizationResult(PrivilegeAdbAuthorizationStatus.AUTHORIZED, null),
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
    fun unauthorizedEndpointStopsRecoveryForExistingAuthorizationFlow() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
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
                authorizationResult(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED, null)
            },
            sleep = { error("zero-delay recovery should not sleep") },
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED, result.status)
        assertEquals(1, checkCalls)
    }

    @Test
    fun exhaustedRecoveryReturnsLastUnavailableResult() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)
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
    fun preparationFailureStaysUnavailableAndAddsDiagnostic() = runBlocking {
        val initial = authorizationResult(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, null)

        val result = recoverTcpAuthorizationForStart(
            initialResult = initial,
            requestedTcpPort = TEST_TCP_PORT,
            configuredTcpPort = TEST_TCP_PORT,
            canManageAdb = true,
            enableAdb = { error("denied") },
            checkAuthorization = { error("authorization should not be checked after preparation failure") },
            sleep = { error("recovery should not wait after preparation failure") },
            retryCount = TEST_RETRY_COUNT,
            retryDelayMillis = PRIVILEGE_ADB_DEFAULT_CONNECT_RETRY_DELAY_MILLIS,
        )

        assertEquals(PrivilegeAdbAuthorizationStatus.UNAVAILABLE, result.status)
        assertTrue(result.failureMessage.orEmpty().contains("denied"))
        assertTrue(result.outputText.contains("Failed to prepare ADB for static TCP"))
    }

    private fun authorizationResult(
        status: PrivilegeAdbAuthorizationStatus,
        failureMessage: String?,
    ): PrivilegeAdbAuthorizationCheckResult =
        PrivilegeAdbAuthorizationCheckResult(
            status = status,
            outputText = "[diag] test",
            identity = PrivilegeAdbIdentity.default(
                deviceName = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
            ),
            failureMessage = failureMessage,
        )

    private companion object {
        private const val TEST_TCP_PORT = 5555
        private const val TEST_RETRY_COUNT = 5
    }
}
