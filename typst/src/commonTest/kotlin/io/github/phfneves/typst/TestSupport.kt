package io.github.phfneves.typst

/** Short name of the platform running the suite, used to name the artifacts it writes. */
internal expect val platformName: String

/**
 * Writes an artifact for a human to open afterwards. Returns its path, or `null` when this
 * platform has nowhere to write — the assertions never depend on it.
 *
 * Every platform with a file system shares one implementation in `fileTest`, over kotlinx-io;
 * only the *directory* differs, and each takes it from wherever its test runner puts it. The web
 * targets have no file system at all and simply return `null`.
 */
internal expect fun writeTestArtifact(name: String, bytes: ByteArray): String?
