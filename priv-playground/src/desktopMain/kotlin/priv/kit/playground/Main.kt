package priv.kit.playground

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Privilege UI · Playground",
        state = rememberWindowState(width = 480.dp, height = 820.dp),
    ) {
        PrivilegePlaygroundApp()
    }
}
