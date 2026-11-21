package priv.kit.ui

/** The decision/input needed to advance pairing, regardless of its visual presentation. */
@ExperimentalPrivilegeUiApi
public enum class PrivilegeUiPairingInteraction {
    ENTER_CODE,
    CONFIRM_WITHOUT_NOTIFICATION,
}
