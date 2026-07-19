package priv.kit.ui.adb.pairing

internal data class PrivilegeAdbPairingInputState(
    val code: String = DEFAULT_PAIRING_INPUT_CODE,
    val selectedIndex: Int = 0,
) {
    init {
        require(code.length == PAIRING_INPUT_CODE_LENGTH && code.all(Char::isDigit)) {
            "Pairing input code must contain exactly $PAIRING_INPUT_CODE_LENGTH digits."
        }
        require(selectedIndex in 0 until PAIRING_INPUT_CODE_LENGTH) {
            "Selected pairing input index must be in 0..${PAIRING_INPUT_CODE_LENGTH - 1}."
        }
    }

    val displayText: String
        get() = code.mapIndexed { index, digit ->
            if (index == selectedIndex) "[$digit]" else digit.toString()
        }.joinToString(" ")

    fun moveLeft(): PrivilegeAdbPairingInputState =
        copy(selectedIndex = (selectedIndex + PAIRING_INPUT_CODE_LENGTH - 1) % PAIRING_INPUT_CODE_LENGTH)

    fun moveRight(): PrivilegeAdbPairingInputState =
        copy(selectedIndex = (selectedIndex + 1) % PAIRING_INPUT_CODE_LENGTH)

    fun incrementDigit(): PrivilegeAdbPairingInputState =
        copy(code = code.replaceDigitAt(selectedIndex) { (it + 1) % 10 })

    fun decrementDigit(): PrivilegeAdbPairingInputState =
        copy(code = code.replaceDigitAt(selectedIndex) { (it + 9) % 10 })
}

internal fun String.isPrivilegeUiPairingCode(): Boolean =
    length == PAIRING_INPUT_CODE_LENGTH && all(Char::isDigit)

private fun String.replaceDigitAt(
    index: Int,
    transform: (Int) -> Int,
): String =
    replaceRange(index, index + 1, transform(this[index].digitToInt()).toString())

private const val PAIRING_INPUT_CODE_LENGTH = 6
private const val DEFAULT_PAIRING_INPUT_CODE = "000000"
