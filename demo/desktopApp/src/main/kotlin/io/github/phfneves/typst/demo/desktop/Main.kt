package io.github.phfneves.typst.demo.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.phfneves.typst.demo.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "typst-kmp demo",
        state = rememberWindowState(size = DpSize(1280.dp, 860.dp)),
    ) {
        App()
    }
}
