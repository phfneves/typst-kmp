package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController

private class IosPdfExporter : PdfExporter {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun share(fileName: String, bytes: ByteArray): String {
        val directory = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).first() as String

        val path = "$directory/$fileName"
        val url = NSURL.fileURLWithPath(path)

        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        data.writeToURL(url, atomically = true)

        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (root != null) {
            val activity = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
            // Required on iPad, where the sheet is a popover and needs something to anchor to.
            activity.popoverPresentationController?.sourceView = root.view
            root.presentViewController(activity, animated = true, completion = null)
        }

        return path
    }
}

@Composable
actual fun rememberPdfExporter(): PdfExporter = remember { IosPdfExporter() }
