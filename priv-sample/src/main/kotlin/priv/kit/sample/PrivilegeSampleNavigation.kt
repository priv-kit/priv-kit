package priv.kit.sample

internal sealed interface PrivilegeSampleRootDestination {
    data object Home : PrivilegeSampleRootDestination
    data object Debug : PrivilegeSampleRootDestination
    data object PrivilegeUi : PrivilegeSampleRootDestination
}
