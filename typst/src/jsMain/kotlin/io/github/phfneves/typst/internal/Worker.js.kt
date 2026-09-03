package io.github.phfneves.typst.internal

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.Worker
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Kotlin/JS half of the worker binding.
 *
 * The mirror of `Worker.wasmJs.kt`; the two differ only in how they reach a JavaScript value —
 * `dynamic` here, `JsAny` there.
 */
internal actual class WorkerHandle(private val worker: Worker) {

    actual fun postInit(id: Int, configJson: String, glueUrl: String, wasmUrl: String) {
        val message = js("{}")
        message.id = id
        message.op = "init"
        message.text = configJson
        message.glueUrl = glueUrl
        message.wasmUrl = wasmUrl
        worker.postMessage(message)
    }

    actual fun post(id: Int, operation: String, text: String?, bytes: ByteArray?) {
        val message = js("{}")
        message.id = id
        message.op = operation
        if (text != null) message.text = text
        if (bytes != null) message.bytes = bytes.asTypedArray()
        worker.postMessage(message)
    }

    actual fun onMessage(handler: (WorkerReply) -> Unit) {
        worker.onmessage = { event -> handler(readReply(event.data)) }
    }

    actual fun terminate() {
        worker.terminate()
    }
}

internal actual fun startWorker(source: String): WorkerHandle {
    val blob = Blob(arrayOf(source), BlobPropertyBag(type = "text/javascript"))
    val url = URL.createObjectURL(blob)
    val worker = Worker(url)
    // The worker has its own copy of the script once it starts, so the object URL is dead weight
    // from here on and would otherwise pin the blob for the lifetime of the page.
    URL.revokeObjectURL(url)
    return WorkerHandle(worker)
}

internal actual fun resolveAgainstDocument(path: String): String =
    URL(path, kotlinx.browser.document.baseURI).href

/**
 * The array as JavaScript sees it, without copying.
 *
 * Kotlin/JS represents a `ByteArray` as exactly an `Int8Array`, so this is a reinterpretation
 * rather than a conversion. It matters: blobs here are whole PDFs and rendered pages, and copying
 * them a byte at a time across the boundary would dominate the compile it just finished.
 */
private fun ByteArray.asTypedArray(): Int8Array = unsafeCast<Int8Array>()

/**
 * A `Uint8Array` as a `ByteArray`, also without copying.
 *
 * The view shares the incoming buffer; the signed/unsigned difference is one of interpretation,
 * not of storage.
 */
private fun Uint8Array.asByteArray(): ByteArray =
    Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

private fun readReply(data: dynamic): WorkerReply {
    val rawBlobs = data.blobs
    val blobs = if (rawBlobs == null) {
        emptyList()
    } else {
        val array: Array<Uint8Array> = rawBlobs.unsafeCast<Array<Uint8Array>>()
        array.map { it.asByteArray() }
    }
    return WorkerReply(
        id = data.id.unsafeCast<Int>(),
        count = if (data.count == null) 0 else data.count.unsafeCast<Int>(),
        json = if (data.json == null) "" else data.json.unsafeCast<String>(),
        blobs = blobs,
        error = if (data.error == null) null else data.error.unsafeCast<String>(),
        fatal = data.fatal == true,
    )
}
