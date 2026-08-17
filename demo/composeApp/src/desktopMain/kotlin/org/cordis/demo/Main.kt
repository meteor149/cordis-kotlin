package org.cordis.demo

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val runtime = remember { DesktopPluginLabRuntime() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Cordis Plugin Lab",
    ) {
        window.minimumSize = java.awt.Dimension(760, 720)
        PluginLabApp(runtime)
    }
}
