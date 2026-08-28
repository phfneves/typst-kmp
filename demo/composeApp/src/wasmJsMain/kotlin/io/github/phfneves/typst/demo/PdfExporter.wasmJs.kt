package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set

/**
 * A browser cannot write to a path, so "sharing" is a download.
 *
 * The mirror of `PdfExporter.js.kt`; Kotlin/Wasm has to copy the bytes into a typed array first,
 * since its own arrays live in linear memory rather than being typed arrays already.
 */
private class BrowserPdfExporter : PdfExporter {

    override suspend fun share(fileName: String, bytes: ByteArray): String {
        val array = allocate(bytes.size)
        for (index in bytes.indices) array[index] = bytes[index]
        download(fileName, array)
        return "Downloaded $fileName"
    }
}

private fun allocate(size: Int): Uint8Array = js("new Uint8Array(size)")

private fun download(fileName: String, bytes: Uint8Array) {
    js(
        """{
            const blob = new Blob([bytes], { type: 'application/pdf' });
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = fileName;
            link.click();
            URL.revokeObjectURL(url);
        }""",
    )
}

@Composable
actual fun rememberPdfExporter(): PdfExporter = remember { BrowserPdfExporter() }
