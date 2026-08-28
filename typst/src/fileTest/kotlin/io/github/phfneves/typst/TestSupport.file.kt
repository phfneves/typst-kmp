package io.github.phfneves.typst

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Where the suite writes inspectable artifacts, or `null` if this platform was not given one.
 *
 * Only the *location* is platform-specific: the JVM takes it from a system property, Kotlin/Native
 * from an environment variable, and the Android instrumentation runner from the app's own external
 * files directory. The writing itself is shared, through kotlinx-io.
 */
internal expect val testOutputDirectory: String?

internal actual fun writeTestArtifact(name: String, bytes: ByteArray): String? {
    val directory = testOutputDirectory ?: return null
    SystemFileSystem.createDirectories(Path(directory))
    val path = Path(directory, name)
    SystemFileSystem.sink(path).buffered().use { sink -> sink.write(bytes) }
    return path.toString()
}
