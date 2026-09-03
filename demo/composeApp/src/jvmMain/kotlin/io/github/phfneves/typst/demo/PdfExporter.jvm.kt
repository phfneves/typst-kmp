package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

private class DesktopPdfExporter : PdfExporter {

    override suspend fun share(fileName: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val downloads = File(System.getProperty("user.home"), "Downloads")
            val directory = if (downloads.isDirectory) downloads else File(System.getProperty("java.io.tmpdir"))
            val file = File(directory, fileName)
            file.writeBytes(bytes)

            // Opening is best effort: a headless session has no viewer to hand the file to, and
            // the path returned below is still useful there.
            runCatching {
                val desktop = Desktop.getDesktop()
                if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file)
                }
            }

            file.absolutePath
        }
}

@Composable
actual fun rememberPdfExporter(): PdfExporter = remember { DesktopPdfExporter() }
