package io.github.phfneves.typst.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Called from `ContentView.swift`; this is the whole bridge between Swift and the shared UI. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
