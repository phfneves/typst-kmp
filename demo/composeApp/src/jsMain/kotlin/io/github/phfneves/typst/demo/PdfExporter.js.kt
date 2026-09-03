package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * A browser cannot write to a path, so "sharing" is a download.
 *
 * The other three platforms hand the file to something that opens PDFs; here the closest
 * equivalent is a synthetic link click, which is what every download button on the web is.
 */
private class BrowserPdfExporter : PdfExporter {

    override suspend fun share(fileName: String, bytes: ByteArray): String {
        // Kotlin/JS represents a ByteArray as an Int8Array, so this hands the bytes over as they
        // already are rather than copying them.
        val blob = Blob(arrayOf(bytes.unsafeCast<Int8Array>()), BlobPropertyBag(type = "application/pdf"))
        val url = URL.createObjectURL(blob)
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = url
        link.download = fileName
        link.click()
        URL.revokeObjectURL(url)
        return "Downloaded $fileName"
    }
}

@Composable
actual fun rememberPdfExporter(): PdfExporter = remember { BrowserPdfExporter() }
