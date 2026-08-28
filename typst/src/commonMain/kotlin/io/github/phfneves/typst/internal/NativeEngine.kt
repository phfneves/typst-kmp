package io.github.phfneves.typst.internal

/**
 * The entire platform-specific surface of this library.
 *
 * Only strings and byte arrays cross here, which is what keeps each `actual` down to roughly a
 * hundred lines and identical in shape across JNI, cinterop and wasm-bindgen.
 *
 * Every call is `suspend` because one platform cannot answer synchronously: on the web the engine
 * lives in a Web Worker, so each of these is a message round-trip. On the JVM and on Kotlin/Native
 * the bodies block, and it is [io.github.phfneves.typst.Typst] that moves them off the caller's
 * thread. [close] is the exception — it stays synchronous so [AutoCloseable] can be honoured, and
 * every platform can release its engine without waiting for an answer.
 */
internal expect class NativeEngine {

    /** Registers every face in a font file; returns how many were added. */
    suspend fun addFont(bytes: ByteArray): Int

    suspend fun vfsPut(path: String, bytes: ByteArray)

    /** Unpacks a `.tar.gz` package archive; returns how many files it contained. */
    suspend fun vfsPutPackage(spec: String, archive: ByteArray): Int

    suspend fun compile(requestJson: String): NativeResult

    fun close()
}

/**
 * Creates and starts an engine.
 *
 * A factory rather than a constructor because starting the web engine is asynchronous — a worker
 * has to come up and a WebAssembly module has to be instantiated — and a constructor cannot
 * suspend. Every other platform ignores everything in [options] but the config JSON.
 */
internal expect suspend fun createNativeEngine(options: EngineOptions): NativeEngine

/**
 * What a platform needs to start an engine.
 *
 * One type for every platform, so the `expect` signature stays common; the web fields are simply
 * unread elsewhere.
 */
internal class EngineOptions(
    /** The engine configuration, as the JSON `typst-kmp-core` expects. */
    val configJson: String,
    /** Web only: where to fetch the WebAssembly module from. See `TypstConfig.webAssetBaseUrl`. */
    val webAssetBaseUrl: String? = null,
)

/** A response envelope plus the output blobs it describes. */
internal class NativeResult(
    val json: String,
    val blobs: List<ByteArray>,
)
