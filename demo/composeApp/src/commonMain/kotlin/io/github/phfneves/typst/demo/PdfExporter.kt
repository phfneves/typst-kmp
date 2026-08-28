package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable

/**
 * Hands a compiled PDF to whatever opens PDFs on this platform.
 *
 * The three implementations have nothing in common — a file plus `java.awt.Desktop`, a
 * `FileProvider` URI plus an `Intent`, a `UIActivityViewController` — so the shared code only ever
 * sees this interface.
 */
interface PdfExporter {
    /** Writes [bytes] out and offers it to the user. Returns where it landed. */
    suspend fun share(fileName: String, bytes: ByteArray): String
}

/**
 * A composable rather than a plain factory because two of the three implementations need something
 * only composition can supply: the Android `Context` and the iOS root view controller.
 */
@Composable
expect fun rememberPdfExporter(): PdfExporter
