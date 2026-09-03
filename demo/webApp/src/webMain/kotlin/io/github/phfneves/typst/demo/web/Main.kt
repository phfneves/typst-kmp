package io.github.phfneves.typst.demo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.phfneves.typst.demo.App

/**
 * The browser entry point, the counterpart of `desktopApp`'s `main`.
 *
 * Shared between the `js` and `wasmJs` distributions: the container is named by id rather than
 * passed as an element, so nothing here touches a JavaScript value and — unlike the engine binding
 * — no per-target half is needed.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "typst-demo") {
        App()
    }
}
