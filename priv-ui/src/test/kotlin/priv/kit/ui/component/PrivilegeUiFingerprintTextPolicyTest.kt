package priv.kit.ui.component

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiFingerprintTextPolicyTest {
    @Test
    fun m3TypeScaleUsesLabelSmallAsMinimumAndBodySmallAsMaximum() {
        val typography = Typography(
            labelSmall = TextStyle(fontSize = 10.sp),
            bodySmall = TextStyle(fontSize = 13.sp),
        )
        val policy = privilegeUiFingerprintTextPolicy(typography)

        requireNotNull(policy)
        assertEquals(typography.labelSmall.fontSize, policy.minFontSize)
        assertEquals(typography.bodySmall.fontSize, policy.maxFontSize)
        assertEquals(0.25.sp, policy.stepSize)
    }

    @Test
    fun invalidTypeScaleFallsBackToWrapping() {
        assertNull(
            privilegeUiFingerprintTextPolicy(
                Typography(
                    labelSmall = TextStyle(fontSize = 12.sp),
                    bodySmall = TextStyle(fontSize = 12.sp),
                ),
            ),
        )
        assertNull(
            privilegeUiFingerprintTextPolicy(
                Typography(
                    labelSmall = TextStyle(fontSize = TextUnit.Unspecified),
                    bodySmall = TextStyle(fontSize = 12.sp),
                ),
            ),
        )
        assertNull(
            privilegeUiFingerprintTextPolicy(
                Typography(
                    labelSmall = TextStyle(fontSize = 0.9.em),
                    bodySmall = TextStyle(fontSize = 12.sp),
                ),
            ),
        )
    }

    @Test
    fun singleLineOnlyFallsBackAfterWidthOverflow() {
        assertFalse(
            privilegeUiFingerprintShouldWrap(
                currentlyWrapped = false,
                didOverflowWidth = false,
                didOverflowHeight = false,
                lineCount = 1,
            ),
        )
        assertTrue(
            privilegeUiFingerprintShouldWrap(
                currentlyWrapped = false,
                didOverflowWidth = true,
                didOverflowHeight = false,
                lineCount = 1,
            ),
        )
        assertTrue(
            privilegeUiFingerprintShouldWrap(
                currentlyWrapped = false,
                didOverflowWidth = false,
                didOverflowHeight = true,
                lineCount = 1,
            ),
        )
    }

    @Test
    fun wrappedTextReturnsToSingleLineWhenItFits() {
        assertTrue(
            privilegeUiFingerprintShouldWrap(
                currentlyWrapped = true,
                didOverflowWidth = false,
                didOverflowHeight = false,
                lineCount = 2,
            ),
        )
        assertFalse(
            privilegeUiFingerprintShouldWrap(
                currentlyWrapped = true,
                didOverflowWidth = false,
                didOverflowHeight = false,
                lineCount = 1,
            ),
        )
    }
}
