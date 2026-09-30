package io.github.eoeo0326.adbmirror

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ADB Mirror") {
        App(platform = "Desktop")
    }
}
