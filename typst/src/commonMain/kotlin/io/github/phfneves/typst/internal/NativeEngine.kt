package io.github.phfneves.typst.internal

/**
 * The entire platform-specific surface of this library.
 *
 * Only strings and byte arrays cross here, which is what keeps each `actual` down to roughly a
 * hundred lines and identical in shape across JNI, cinterop and (later) wasm-bindgen.
 */
internal expect class NativeEngine(configJson: String) {

    /** Registers every face in a font file; returns how many were added. */
    fun addFont(bytes: ByteArray): Int

    fun vfsPut(path: String, bytes: ByteArray)

    /** Unpacks a `.tar.gz` package archive; returns how many files it contained. */
    fun vfsPutPackage(spec: String, archive: ByteArray): Int

    fun compile(requestJson: String): NativeResult

    fun close()
}

/** A response envelope plus the output blobs it describes. */
internal class NativeResult(
    val json: String,
    val blobs: List<ByteArray>,
)
