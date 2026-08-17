package priv.kit.sample

internal sealed interface PrivilegeSampleRootDestination {
    data object Home : PrivilegeSampleRootDestination
    data object Debug : PrivilegeSampleRootDestination
    data object DeviceFiles : PrivilegeSampleRootDestination
    data object FileApi : PrivilegeSampleRootDestination
    data object PrivilegeUi : PrivilegeSampleRootDestination
}
