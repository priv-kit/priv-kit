package priv.kit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbPairingInputStateTest {
    @Test
    fun formatsSelectedDigit() {
        assertEquals(
            "[0] 0 0 0 0 0",
            PrivilegeAdbPairingInputState().displayText,
        )
    }

    @Test
    fun movesSelectionWithOverflow() {
        val state = PrivilegeAdbPairingInputState()

        assertEquals(
            5,
            state.moveLeft().selectedIndex,
        )
        assertEquals(
            0,
            state.copy(selectedIndex = 5).moveRight().selectedIndex,
        )
    }

    @Test
    fun changesDigitWithOverflow() {
        assertEquals(
            "100000",
            PrivilegeAdbPairingInputState().incrementDigit().code,
        )
        assertEquals(
            "900000",
            PrivilegeAdbPairingInputState().decrementDigit().code,
        )
        assertEquals(
            "900000",
            PrivilegeAdbPairingInputState(
                code = "990000",
                selectedIndex = 1,
            ).incrementDigit().code,
        )
    }

    @Test
    fun restoresDigitsAndWrappedIndex() {
        val state = PrivilegeAdbPairingInputState.fromPairingCode(
            code = "a12b",
            selectedIndex = -1,
        )

        assertEquals("120000", state.code)
        assertEquals(5, state.selectedIndex)
        assertEquals("1 2 0 0 0 [0]", state.displayText)
    }

    @Test
    fun validatesExactlySixDigits() {
        assertTrue("123456".isPrivilegeUiPairingCode())
        assertFalse("12345".isPrivilegeUiPairingCode())
        assertFalse("1234567".isPrivilegeUiPairingCode())
        assertFalse("123 456".isPrivilegeUiPairingCode())
        assertFalse("12345a".isPrivilegeUiPairingCode())
    }
}
