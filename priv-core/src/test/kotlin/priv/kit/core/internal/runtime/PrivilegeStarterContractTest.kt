package priv.kit.core.internal.runtime

import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.PrivilegeExistingServerStopException

class PrivilegeStarterContractTest {
    @Test
    fun stopFailureMarkerThrowsStructuredFailure() {
        assertThrows(PrivilegeExistingServerStopException::class.java) {
            PrivilegeStarterContract.requireNoStopExistingServerFailure(
                "fatal: ${PrivilegeStarterContract.STOP_EXISTING_SERVER_FAILED_MARKER}: " +
                    "Operation not permitted",
            )
        }
    }

    @Test
    fun unrelatedStarterOutputIsAccepted() {
        PrivilegeStarterContract.requireNoStopExistingServerFailure(
            "info: starter exit with 0",
        )
    }
}
