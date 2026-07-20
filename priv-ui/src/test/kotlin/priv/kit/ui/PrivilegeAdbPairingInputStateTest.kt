package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

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
    fun validatesExactlySixDigits() {
        assertTrue("123456".isPrivilegeUiPairingCode())
        assertFalse("12345".isPrivilegeUiPairingCode())
        assertFalse("1234567".isPrivilegeUiPairingCode())
        assertFalse("123 456".isPrivilegeUiPairingCode())
        assertFalse("12345a".isPrivilegeUiPairingCode())
        assertFalse("١٢٣٤٥٦".isPrivilegeUiPairingCode())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonAsciiPairingInputState() {
        PrivilegeAdbPairingInputState(code = "١٢٣٤٥٦")
    }
}
