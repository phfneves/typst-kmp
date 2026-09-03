package io.github.phfneves.typst

/**
 * A browser has nowhere to write a file the way the other platforms do, so the suite runs without
 * leaving a PDF behind. Every assertion still holds; only the human-inspectable copy is missing.
 */
internal actual fun writeTestArtifact(name: String, bytes: ByteArray): String? = null
