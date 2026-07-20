package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.ui.state.PrivilegeUiFailureKind
import priv.kit.ui.state.privilegeUiRuntimeStartFailureKind
import priv.kit.ui.state.privilegeUiTcpAuthorizationFailureKind
import priv.kit.ui.state.toPrivilegeUiDiagnosticString

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiFailurePolicyTest {
    @Test
    fun nestedRootUnavailableFailureUsesSpecificKind() {
        val failure = IllegalStateException(
            "outer failure",
            PrivilegeStartupException("Root is not available"),
        )

        assertEquals(
            PrivilegeUiFailureKind.ROOT_UNAVAILABLE,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ROOT, failure),
        )
    }

    @Test
    fun similarRootDiagnosticFallsBackToLocalizedRootFailureKind() {
        val failure = PrivilegeStartupException("Root is not available right now")

        assertEquals(
            PrivilegeUiFailureKind.ROOT_START_FAILED,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ROOT, failure),
        )
    }

    @Test
    fun unknownStartFailuresUseSourceSpecificKinds() {
        val failure = PrivilegeStartupException("injected technical message")

        assertEquals(
            PrivilegeUiFailureKind.ROOT_START_FAILED,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ROOT, failure),
        )
        assertEquals(
            PrivilegeUiFailureKind.ADB_START_FAILED,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ADB_WIRELESS, failure),
        )
        assertEquals(
            PrivilegeUiFailureKind.ADB_START_FAILED,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP, failure),
        )
        assertEquals(
            PrivilegeUiFailureKind.EXTERNAL_START_FAILED,
            privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.EXTERNAL, failure),
        )
        assertEquals(
            PrivilegeUiFailureKind.START_FAILED,
            privilegeUiRuntimeStartFailureKind(null, failure),
        )
    }

    @Test
    fun tcpAuthorizationFailureUsesStructuredEndReason() {
        assertEquals(
            PrivilegeUiFailureKind.TCP_AUTHORIZATION_FAILED,
            privilegeUiTcpAuthorizationFailureKind(PrivilegeAdbAuthorizationEndReason.FAILED),
        )
        listOf(
            PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
            PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
            null,
        ).forEach { endReason ->
            assertEquals(
                PrivilegeUiFailureKind.TCP_AUTHORIZATION_NOT_COMPLETED,
                privilegeUiTcpAuthorizationFailureKind(endReason),
            )
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseFailureResourcesNeverExposeInjectedDiagnostic() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val diagnostic = "Failed to execute injected command"
        val failure = PrivilegeStartupException(diagnostic)
        val kind = privilegeUiRuntimeStartFailureKind(PrivilegeUiRuntimeStartSource.ROOT, failure)
        val userMessage = context.getString(kind.messageResId)

        assertEquals("Root 启动失败，请查看启动日志", userMessage)
        assertFalse(userMessage.contains(diagnostic))
        assertTrue(failure.toPrivilegeUiDiagnosticString().contains(diagnostic))
    }
}
