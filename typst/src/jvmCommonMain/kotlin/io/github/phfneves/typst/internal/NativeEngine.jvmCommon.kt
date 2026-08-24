package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException

/**
 * Loads `libtypst_kmp_jni`. The JVM extracts it from the jar; Android finds it in the APK.
 */
internal expect fun loadTypstNativeLibrary()

/**
 * Raw JNI entry points.
 *
 * These are *instance* native methods on the object singleton, so the Rust side receives the
 * receiver as its second argument. Keep the fully qualified name in sync with the symbol names in
 * `rust/typst-kmp-jni/src/lib.rs` (`Java_io_github_phfneves_typst_internal_TypstNative_*`).
 */
internal object TypstNative {

    init {
        loadTypstNativeLibrary()
    }

    external fun engineNew(configJson: String): Long

    external fun engineFree(handle: Long)

    external fun engineAddFont(handle: Long, data: ByteArray): Int

    external fun vfsPut(handle: Long, path: String, data: ByteArray)

    external fun vfsPutPackage(handle: Long, spec: String, data: ByteArray): Int

    /** Returns `arrayOf(responseJson: String, blobs: Array<ByteArray>)`. */
    external fun compile(handle: Long, requestJson: String): Array<Any>

    external fun nativeVersion(): String
}

internal actual class NativeEngine actual constructor(configJson: String) {

    private var handle: Long = TypstNative.engineNew(configJson)

    actual fun addFont(bytes: ByteArray): Int = TypstNative.engineAddFont(alive(), bytes)

    actual fun vfsPut(path: String, bytes: ByteArray) {
        TypstNative.vfsPut(alive(), path, bytes)
    }

    actual fun vfsPutPackage(spec: String, archive: ByteArray): Int =
        TypstNative.vfsPutPackage(alive(), spec, archive)

    actual fun compile(requestJson: String): NativeResult {
        val raw = TypstNative.compile(alive(), requestJson)
        val json = raw[0] as String

        @Suppress("UNCHECKED_CAST")
        val blobs = raw[1] as Array<ByteArray>
        return NativeResult(json, blobs.asList())
    }

    actual fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            TypstNative.engineFree(current)
        }
    }

    private fun alive(): Long {
        if (handle == 0L) throw TypstNativeException("The native Typst engine is already closed.")
        return handle
    }
}
