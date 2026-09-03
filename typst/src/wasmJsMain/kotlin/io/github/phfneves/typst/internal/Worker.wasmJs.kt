package io.github.phfneves.typst.internal

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.Worker

/**
 * Kotlin/Wasm half of the worker binding.
 *
 * The mirror of `Worker.js.kt`. The message objects are built and read in small `js("…")`
 * functions, because Kotlin/Wasm has no `dynamic` and every JavaScript value it holds is a
 * `JsAny`.
 */
internal actual class WorkerHandle(private val worker: Worker) {

    actual fun postInit(id: Int, configJson: String, glueUrl: String, wasmUrl: String) {
        postMessage(worker, initMessage(id, configJson, glueUrl, wasmUrl))
    }

    actual fun post(id: Int, operation: String, text: String?, bytes: ByteArray?) {
        postMessage(worker, message(id, operation, text, bytes?.toUint8Array()))
    }

    actual fun onMessage(handler: (WorkerReply) -> Unit) {
        setOnMessage(worker) { data -> handler(readReply(data)) }
    }

    actual fun terminate() {
        worker.terminate()
    }
}

internal actual fun startWorker(source: String): WorkerHandle = WorkerHandle(spawnWorker(source))

internal actual fun resolveAgainstDocument(path: String): String = resolveUrl(path)

// --- JavaScript glue ---------------------------------------------------------------------------

private fun spawnWorker(source: String): Worker = js(
    """{
        const blob = new Blob([source], { type: 'text/javascript' });
        const url = URL.createObjectURL(blob);
        const worker = new Worker(url);
        URL.revokeObjectURL(url);
        return worker;
    }""",
)

private fun postMessage(worker: Worker, request: JsAny) {
    js("worker.postMessage(request)")
}

private fun setOnMessage(worker: Worker, handler: (JsAny) -> Unit) {
    js("worker.onmessage = (event) => handler(event.data)")
}

private fun initMessage(id: Int, configJson: String, glueUrl: String, wasmUrl: String): JsAny = js(
    "({ id: id, op: 'init', text: configJson, glueUrl: glueUrl, wasmUrl: wasmUrl })",
)

private fun message(id: Int, op: String, text: String?, bytes: Uint8Array?): JsAny = js(
    """{
        const request = { id: id, op: op };
        if (text !== null) request.text = text;
        if (bytes !== null) request.bytes = bytes;
        return request;
    }""",
)

private fun resolveUrl(path: String): String = js("new URL(path, document.baseURI).href")

private fun replyId(reply: JsAny): Int = js("reply.id")

private fun replyCount(reply: JsAny): Int = js("reply.count === undefined ? 0 : reply.count")

private fun replyJson(reply: JsAny): String = js("reply.json === undefined ? '' : reply.json")

private fun replyError(reply: JsAny): String? = js("reply.error === undefined ? null : reply.error")

private fun replyFatal(reply: JsAny): Boolean = js("reply.fatal === true")

private fun replyBlobCount(reply: JsAny): Int = js(
    "reply.blobs === undefined ? 0 : reply.blobs.length",
)

private fun replyBlob(reply: JsAny, index: Int): Uint8Array = js("reply.blobs[index]")

private fun allocate(size: Int): Uint8Array = js("new Uint8Array(size)")

// --- conversions -------------------------------------------------------------------------------

private fun readReply(reply: JsAny): WorkerReply {
    val count = replyBlobCount(reply)
    val blobs = ArrayList<ByteArray>(count)
    for (index in 0 until count) {
        blobs += replyBlob(reply, index).toByteArray()
    }
    return WorkerReply(
        id = replyId(reply),
        count = replyCount(reply),
        json = replyJson(reply),
        blobs = blobs,
        error = replyError(reply),
        fatal = replyFatal(reply),
    )
}

/*
 * Both conversions copy element by element, and each element is a call into JavaScript.
 *
 * Kotlin/JS gets this for free — there a `ByteArray` *is* an `Int8Array` — but Kotlin/Wasm keeps
 * its arrays in linear memory, and the standard library offers no bulk copy to a typed array. At
 * roughly tens of nanoseconds per byte this costs single-digit milliseconds for a PDF and a
 * fraction of a second for a large set of rendered pages: real, but small next to the compilation
 * that produced them.
 */
private fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(length)
    for (index in result.indices) result[index] = this[index]
    return result
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val result = allocate(size)
    for (index in indices) result[index] = this[index]
    return result
}
