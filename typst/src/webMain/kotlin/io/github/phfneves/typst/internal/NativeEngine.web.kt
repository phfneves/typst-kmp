package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The Typst engine, running in a Web Worker.
 *
 * JavaScript gives a page one thread, and typesetting a document takes seconds, so the engine is
 * pushed into a worker of its own and driven by messages. That is the whole reason every method on
 * [NativeEngine] suspends: unlike JNI and cinterop, this binding genuinely cannot answer on the
 * spot.
 *
 * The worker itself is [TypstWorkerScript.SOURCE], a string compiled into this library and started
 * from a `Blob`. Nothing has to be hosted, no bundler has to resolve it, and it is always
 * same-origin — which is what leaves the WebAssembly module free to live on a CDN.
 *
 * Requests are tagged with an id and parked in [pending], so several coroutines can share one
 * engine exactly as they can on the other platforms. The worker handles them one at a time.
 */
internal actual class NativeEngine private constructor(private val worker: WorkerHandle) {

    private var nextId = 1
    private val pending = mutableMapOf<Int, Continuation<WorkerReply>>()

    /**
     * Set once the WebAssembly module has aborted.
     *
     * `wasm32-unknown-unknown` cannot unwind, so a Rust panic destroys the instance rather than
     * raising through it. There is no recovering inside this engine; the only honest answer is to
     * fail every call from here on with a message that says so.
     */
    private var fatal: String? = null

    private var closed = false

    actual suspend fun addFont(bytes: ByteArray): Int = call("addFont", bytes = bytes).count

    actual suspend fun vfsPut(path: String, bytes: ByteArray) {
        call("vfsPut", text = path, bytes = bytes)
    }

    actual suspend fun vfsPutPackage(spec: String, archive: ByteArray): Int =
        call("vfsPutPackage", text = spec, bytes = archive).count

    actual suspend fun compile(requestJson: String): NativeResult {
        val reply = call("compile", text = requestJson)
        return NativeResult(reply.json, reply.blobs)
    }

    actual fun close() {
        if (closed) return
        closed = true
        // Terminating drops the module, the engine and any work in flight in one go, so there is
        // nothing to await and no free() to call.
        worker.terminate()
        val outstanding = pending.values.toList()
        pending.clear()
        outstanding.forEach {
            it.resumeWithException(
                TypstNativeException("The Typst engine was closed while a call was in flight."),
            )
        }
    }

    private suspend fun call(
        operation: String,
        text: String? = null,
        bytes: ByteArray? = null,
    ): WorkerReply {
        if (closed) throw TypstNativeException("The Typst engine is already closed.")
        fatal?.let { throw TypstNativeException(it) }

        val id = nextId++
        return suspendCoroutine { continuation ->
            pending[id] = continuation
            worker.post(id, operation, text, bytes)
        }
    }

    /** Called by the platform bridge for every message the worker sends back. */
    private fun deliver(reply: WorkerReply) {
        val continuation = pending.remove(reply.id) ?: return
        val error = reply.error
        if (error == null) {
            continuation.resume(reply)
            return
        }
        if (reply.fatal) {
            val message = FATAL_PREFIX + error
            fatal = message
            // The module is gone, so nothing else in flight can ever be answered either.
            val outstanding = pending.values.toList()
            pending.clear()
            outstanding.forEach { it.resumeWithException(TypstNativeException(message)) }
            continuation.resumeWithException(TypstNativeException(message))
            return
        }
        continuation.resumeWithException(TypstNativeException(error))
    }

    internal companion object {
        private const val FATAL_PREFIX =
            "The Typst WebAssembly module aborted and this engine cannot be reused; " +
                "create a new Typst instance. Cause: "

        suspend fun open(options: EngineOptions): NativeEngine {
            val base = normaliseBaseUrl(options.webAssetBaseUrl ?: DEFAULT_ASSET_PATH)
            val engine = NativeEngine(startWorker(TypstWorkerScript.SOURCE))
            engine.worker.onMessage { reply -> engine.deliver(reply) }
            try {
                engine.initialise(
                    configJson = options.configJson,
                    glueUrl = base + GLUE_FILE,
                    wasmUrl = base + MODULE_FILE,
                )
            } catch (failure: Throwable) {
                engine.close()
                throw failure
            }
            return engine
        }
    }

    /**
     * Brings the worker up: it loads the glue, instantiates the module and constructs the engine.
     *
     * Separate from [call] because it is the one operation that may run while [fatal] is set — it
     * is what clears it — and because its reply carries nothing.
     */
    private suspend fun initialise(configJson: String, glueUrl: String, wasmUrl: String) {
        val id = nextId++
        suspendCoroutine { continuation: Continuation<WorkerReply> ->
            pending[id] = continuation
            worker.postInit(id, configJson, glueUrl, wasmUrl)
        }
    }
}

internal actual suspend fun createNativeEngine(options: EngineOptions): NativeEngine =
    NativeEngine.open(options)

/** Where the two assets live when nothing says otherwise. Relative to the page. */
private const val DEFAULT_ASSET_PATH = "typst-kmp/"

private const val GLUE_FILE = "typst_kmp_wasm.js"
private const val MODULE_FILE = "typst_kmp_wasm_bg.wasm"

/**
 * Makes [base] an absolute URL ending in a slash.
 *
 * Absolute because the worker starts from a `blob:` URL, against which a relative path resolves to
 * nothing useful — so the page, which does have a real base, has to do the resolving.
 */
private fun normaliseBaseUrl(base: String): String {
    val withSlash = if (base.endsWith('/')) base else "$base/"
    return resolveAgainstDocument(withSlash)
}

/** One reply from the worker. `count` and `json` are only meaningful for the operations that set them. */
internal class WorkerReply(
    val id: Int,
    val count: Int,
    val json: String,
    val blobs: List<ByteArray>,
    val error: String?,
    val fatal: Boolean,
)

/**
 * The per-target half of the worker binding.
 *
 * Only this much is duplicated between `jsMain` and `wasmJsMain`: Kotlin/JS speaks `dynamic` and
 * Kotlin/Wasm speaks `JsAny`, and no amount of shared code bridges those two. Everything above is
 * written once.
 */
internal expect class WorkerHandle {

    fun postInit(id: Int, configJson: String, glueUrl: String, wasmUrl: String)

    fun post(id: Int, operation: String, text: String?, bytes: ByteArray?)

    fun onMessage(handler: (WorkerReply) -> Unit)

    fun terminate()
}

/** Starts a worker running [source], which is JavaScript, not a URL. */
internal expect fun startWorker(source: String): WorkerHandle

/** Resolves [path] against the document's base URL, yielding an absolute one. */
internal expect fun resolveAgainstDocument(path: String): String
