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

    external fun vfsRemove(handle: Long, path: String): Boolean

    external fun vfsClearFiles(handle: Long): Int

    external fun vfsClearPackages(handle: Long): Int

    /**
     * Returns `arrayOf(responseJson: String, blobs: Array<ByteArray>)`. [files] holds the bytes of
     * the request's files, in the order its JSON lists their paths.
     */
    external fun compile(
        handle: Long,
        requestJson: String,
        files: Array<ByteArray>,
        cancel: Long,
    ): Array<Any>

    /** A new cancellation token for [compile], released with [cancelFree]. */
    external fun cancelNew(): Long

    /** Sets a token, from any thread; the compilation holding it stops soon after. */
    external fun cancel(token: Long)

    external fun cancelFree(token: Long)

    external fun inspect(handle: Long): String

    external fun nativeVersion(): String
}

internal actual class NativeEngine private constructor(configJson: String) {

    private var handle: Long = TypstNative.engineNew(configJson)

    actual suspend fun addFont(bytes: ByteArray): Int = TypstNative.engineAddFont(alive(), bytes)

    actual suspend fun vfsPut(path: String, bytes: ByteArray) {
        TypstNative.vfsPut(alive(), path, bytes)
    }

    actual suspend fun vfsPutPackage(spec: String, archive: ByteArray): Int =
        TypstNative.vfsPutPackage(alive(), spec, archive)

    actual suspend fun vfsRemove(path: String): Boolean = TypstNative.vfsRemove(alive(), path)

    actual suspend fun vfsClearFiles(): Int = TypstNative.vfsClearFiles(alive())

    actual suspend fun vfsClearPackages(): Int = TypstNative.vfsClearPackages(alive())

    actual suspend fun inspect(): String = TypstNative.inspect(alive())

    actual suspend fun compile(requestJson: String, files: List<ByteArray>): NativeResult {
        val handle = alive()
        val token = TypstNative.cancelNew()
        val raw = try {
            interruptible({ TypstNative.cancel(token) }) {
                TypstNative.compile(handle, requestJson, files.toTypedArray(), token)
            }
        } finally {
            TypstNative.cancelFree(token)
        }
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

    internal companion object {
        fun open(configJson: String): NativeEngine = NativeEngine(configJson)
    }
}

/** Nothing here is asynchronous; JNI hands the engine back on the calling thread. */
internal actual suspend fun createNativeEngine(options: EngineOptions): NativeEngine =
    NativeEngine.open(options.configJson)
