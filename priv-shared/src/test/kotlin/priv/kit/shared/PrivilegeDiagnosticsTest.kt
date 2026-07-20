package priv.kit.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class PrivilegeDiagnosticsTest {
    @Test
    public fun formatsCauseChainAndStackFrames() {
        val inner = IllegalArgumentException("inner").withStackFrames(
            StackTraceElement("InnerClass", "innerCall", "Inner.kt", 20),
        )
        val outer = IllegalStateException("outer", inner).withStackFrames(
            StackTraceElement("OuterClass", "firstCall", "Outer.kt", 10),
            StackTraceElement("OuterClass", "secondCall", "Outer.kt", 11),
        )

        assertEquals(
            listOf(
                "Cause[0]: java.lang.IllegalStateException: outer",
                "  at OuterClass.firstCall(Outer.kt:10)",
                "  at OuterClass.secondCall(Outer.kt:11)",
                "Cause[1]: java.lang.IllegalArgumentException: inner",
                "  at InnerClass.innerCall(Inner.kt:20)",
            ).joinToString("\n"),
            outer.toPrivilegeDiagnosticString(),
        )
    }

    @Test
    public fun honorsCauseAndStackLimits() {
        val inner = IllegalArgumentException("inner").withStackFrames(
            StackTraceElement("InnerClass", "innerCall", "Inner.kt", 20),
        )
        val outer = IllegalStateException("outer", inner).withStackFrames(
            StackTraceElement("OuterClass", "firstCall", "Outer.kt", 10),
            StackTraceElement("OuterClass", "secondCall", "Outer.kt", 11),
        )

        val diagnostic = outer.toPrivilegeDiagnosticString(
            maxCauseDepth = 1,
            maxStackFramesPerCause = 1,
        )

        assertEquals(
            listOf(
                "Cause[0]: java.lang.IllegalStateException: outer",
                "  at OuterClass.firstCall(Outer.kt:10)",
            ).joinToString("\n"),
            diagnostic,
        )
        assertFalse(diagnostic.contains("inner"))
        assertFalse(diagnostic.contains("secondCall"))
    }

    @Test
    public fun rejectsNonPositiveLimits() {
        listOf(0, -1).forEach { invalidLimit ->
            val causeDepthFailure = assertThrows(IllegalArgumentException::class.java) {
                Throwable().toPrivilegeDiagnosticString(maxCauseDepth = invalidLimit)
            }
            assertTrue(causeDepthFailure.message.orEmpty().contains("maxCauseDepth"))

            val stackFramesFailure = assertThrows(IllegalArgumentException::class.java) {
                Throwable().toPrivilegeDiagnosticString(maxStackFramesPerCause = invalidLimit)
            }
            assertTrue(stackFramesFailure.message.orEmpty().contains("maxStackFramesPerCause"))
        }
    }

    private fun <T : Throwable> T.withStackFrames(vararg frames: StackTraceElement): T =
        apply { stackTrace = frames }
}
