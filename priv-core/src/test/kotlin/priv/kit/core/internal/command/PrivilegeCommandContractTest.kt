package priv.kit.core.internal.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.command.PrivilegeCommandException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeCommandContractTest {
    @Test
    fun requestRoundTripsAndroidBuiltInValues() {
        val command = PrivilegeCommand(
            arguments = listOf("/system/bin/sh", "-c", "printf ok"),
            environment = mapOf("LANG" to "C"),
            workingDirectory = "/data/local/tmp",
        )

        val decoded = PrivilegeCommandContract.requestFrom(
            PrivilegeCommandContract.requestBundle(command, timeoutMillis = 12_345L),
        )

        assertEquals(command.arguments, decoded.command.arguments)
        assertEquals(command.environment, decoded.command.environment)
        assertEquals(command.workingDirectory, decoded.command.workingDirectory)
        assertEquals(12_345L, decoded.timeoutMillis)
    }

    @Test
    fun nullTimeoutUsesZeroWireValue() {
        val decoded = PrivilegeCommandContract.requestFrom(
            PrivilegeCommandContract.requestBundle(
                PrivilegeCommand(listOf("id")),
                timeoutMillis = null,
            ),
        )

        assertEquals(0L, decoded.timeoutMillis)
    }

    @Test
    fun invalidArgumentsAreRejected() {
        val bundle = android.os.Bundle().apply {
            putStringArray(PrivilegeCommandContract.KEY_ARGUMENTS, emptyArray())
        }

        assertThrows(PrivilegeCommandException::class.java) {
            PrivilegeCommandContract.requestFrom(bundle)
        }
    }
}
