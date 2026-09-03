package io.github.phfneves.typst.demo

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class AndroidPdfExporter(private val context: Context) : PdfExporter {

    override suspend fun share(fileName: String, bytes: ByteArray): String {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, SHARED_DIRECTORY).apply { mkdirs() }
            File(directory, fileName).apply { writeBytes(bytes) }
        }

        // The authority has to match the <provider> declared in androidApp's manifest, and the
        // directory has to match the <cache-path> it points at.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(view, "Abrir PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        return file.absolutePath
    }

    private companion object {
        const val SHARED_DIRECTORY = "shared"
    }
}

@Composable
actual fun rememberPdfExporter(): PdfExporter {
    val context = LocalContext.current
    return remember(context) { AndroidPdfExporter(context) }
}
