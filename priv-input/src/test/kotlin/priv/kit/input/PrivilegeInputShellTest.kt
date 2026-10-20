package priv.kit.input

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.input.internal.checkInputCommandResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE)
class PrivilegeInputShellTest {
    @Test @Config(sdk = [30]) fun olderSystemIsRejectedBeforeTransaction() {
        val input = PrivilegeInput(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                fail("Must not dispatch on Android 11")
                return false
            }
        })
        assertThrows(IllegalStateException::class.java) { input.shellCommand("tap", "1", "2") }
    }

    @Test fun nonzeroExitCodeRetainsCodeAndUnmodifiedOutput() {
        for (output in listOf("", "  failure details\n")) {
            val error = assertThrows(InputShellCommandException::class.java) { checkInputCommandResult(7, output) }
            assertEquals(7, error.exitCode)
            assertEquals(output, error.output)
        }
    }

    @Test fun textualErrorsAreCheckedEvenWithZeroExitCode() {
        for (output in listOf("Unknown command: nope", "Error: Invalid arguments")) {
            val error = assertThrows(InputShellCommandException::class.java) { checkInputCommandResult(0, output) }
            assertEquals(0, error.exitCode)
            assertEquals(output, error.output)
        }
        checkInputCommandResult(0, "Input help\n  tap <x> <y>")
    }
}
