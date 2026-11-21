package priv.kit.ui.state

public fun privilegeUiStaticTcpOpenCommand(tcpPort: Int): String =
    "adb tcpip $tcpPort"
