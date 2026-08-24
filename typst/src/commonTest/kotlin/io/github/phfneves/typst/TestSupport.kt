package io.github.phfneves.typst

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Short name of the platform running the suite, used to name the artifacts it writes. */
internal expect val platformName: String

/**
 * Where the suite writes inspectable artifacts, or `null` if this platform was not given one.
 *
 * Only the *location* is platform-specific: the JVM takes it from a system property, Kotlin/Native
 * from an environment variable, and the Android instrumentation runner from the app's own external
 * files directory. The writing itself is shared, through kotlinx-io.
 */
internal expect val testOutputDirectory: String?

/**
 * Writes an artifact for a human to open afterwards. Returns its path, or `null` when this
 * platform has nowhere to write — the assertions never depend on it.
 */
internal fun writeTestArtifact(name: String, bytes: ByteArray): String? {
    val directory = testOutputDirectory ?: return null
    SystemFileSystem.createDirectories(Path(directory))
    val path = Path(directory, name)
    SystemFileSystem.sink(path).buffered().use { sink -> sink.write(bytes) }
    return path.toString()
}
