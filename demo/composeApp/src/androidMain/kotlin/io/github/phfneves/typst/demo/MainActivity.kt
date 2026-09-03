package io.github.phfneves.typst.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The Android entry point.
 *
 * It lives here rather than in `:androidApp` because this is the only Android source set with the
 * Compose compiler applied — AGP 9 forbids the Kotlin Multiplatform plugin in an application
 * module, so that module carries the manifest and nothing else.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
