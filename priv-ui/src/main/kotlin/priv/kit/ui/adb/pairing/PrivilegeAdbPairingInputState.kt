package priv.kit.ui.adb.pairing

import priv.kit.ui.*
import priv.kit.ui.adb.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

internal data class PrivilegeAdbPairingInputState(
    internal val code: String = DEFAULT_PAIRING_INPUT_CODE,
    internal val selectedIndex: Int = 0,
) {
    init {
        require(code.length == PAIRING_INPUT_CODE_LENGTH && code.all(Char::isDigit)) {
            "Pairing input code must contain exactly $PAIRING_INPUT_CODE_LENGTH digits."
        }
        require(selectedIndex in 0 until PAIRING_INPUT_CODE_LENGTH) {
            "Selected pairing input index must be in 0..${PAIRING_INPUT_CODE_LENGTH - 1}."
        }
    }

    internal val displayText: String
        get() = code.mapIndexed { index, digit ->
            if (index == selectedIndex) "[$digit]" else digit.toString()
        }.joinToString(" ")

    internal fun moveLeft(): PrivilegeAdbPairingInputState =
        copy(selectedIndex = (selectedIndex + PAIRING_INPUT_CODE_LENGTH - 1) % PAIRING_INPUT_CODE_LENGTH)

    internal fun moveRight(): PrivilegeAdbPairingInputState =
        copy(selectedIndex = (selectedIndex + 1) % PAIRING_INPUT_CODE_LENGTH)

    internal fun incrementDigit(): PrivilegeAdbPairingInputState =
        copy(code = code.replaceDigitAt(selectedIndex) { (it + 1) % 10 })

    internal fun decrementDigit(): PrivilegeAdbPairingInputState =
        copy(code = code.replaceDigitAt(selectedIndex) { (it + 9) % 10 })

    internal companion object {
        internal fun fromPairingCode(
            code: String,
            selectedIndex: Int,
        ): PrivilegeAdbPairingInputState =
            PrivilegeAdbPairingInputState(
                code = code.filter(Char::isDigit)
                    .padEnd(PAIRING_INPUT_CODE_LENGTH, '0')
                    .take(PAIRING_INPUT_CODE_LENGTH),
                selectedIndex = selectedIndex.floorMod(PAIRING_INPUT_CODE_LENGTH),
            )
    }
}

internal fun String.toPrivilegeAdbPairingCodeDigits(): String =
    filter(Char::isDigit).take(PAIRING_INPUT_CODE_LENGTH)

internal fun String.isPrivilegeUiPairingCode(): Boolean =
    length == PAIRING_INPUT_CODE_LENGTH && all(Char::isDigit)

private fun String.replaceDigitAt(
    index: Int,
    transform: (Int) -> Int,
): String =
    replaceRange(index, index + 1, transform(this[index].digitToInt()).toString())

private fun Int.floorMod(divisor: Int): Int =
    ((this % divisor) + divisor) % divisor

private const val PAIRING_INPUT_CODE_LENGTH = 6
private const val DEFAULT_PAIRING_INPUT_CODE = "000000"
