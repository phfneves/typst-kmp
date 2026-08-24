package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException
import io.github.phfneves.typst.cinterop.typst_kmp_compile
import io.github.phfneves.typst.cinterop.typst_kmp_engine_add_font
import io.github.phfneves.typst.cinterop.typst_kmp_engine_free
import io.github.phfneves.typst.cinterop.typst_kmp_engine_new
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_put
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_put_package
import io.github.phfneves.typst.cinterop.typst_kmp_result_blob
import io.github.phfneves.typst.cinterop.typst_kmp_result_blob_count
import io.github.phfneves.typst.cinterop.typst_kmp_result_free
import io.github.phfneves.typst.cinterop.typst_kmp_result_json
import io.github.phfneves.typst.cinterop.typst_kmp_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeEngine actual constructor(configJson: String) {

    private var handle: CPointer<cnames.structs.TypstKmpEngine>? = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        typst_kmp_engine_new(configJson, error.ptr)
            ?: fail(error, "Failed to create the native Typst engine.")
    }

    actual fun addFont(bytes: ByteArray): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val added = bytes.withBuffer { pointer, length ->
            typst_kmp_engine_add_font(alive(), pointer, length, error.ptr)
        }
        if (added < 0) fail(error, "Failed to register the font.")
        added
    }

    actual fun vfsPut(path: String, bytes: ByteArray): Unit = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val status = bytes.withBuffer { pointer, length ->
            typst_kmp_engine_vfs_put(alive(), path, pointer, length, error.ptr)
        }
        if (status != 0) fail(error, "Failed to write $path into the virtual file system.")
    }

    actual fun vfsPutPackage(spec: String, archive: ByteArray): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val count = archive.withBuffer { pointer, length ->
            typst_kmp_engine_vfs_put_package(alive(), spec, pointer, length, error.ptr)
        }
        if (count < 0) fail(error, "Failed to unpack the package $spec.")
        count
    }

    actual fun compile(requestJson: String): NativeResult {
        val result = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            typst_kmp_compile(alive(), requestJson, error.ptr)
                ?: fail(error, "The native Typst engine failed to compile.")
        }
        try {
            val json = typst_kmp_result_json(result)?.toKString()
                ?: throw TypstNativeException("The native Typst engine returned no response.")
            val count = typst_kmp_result_blob_count(result).toInt()
            val blobs = ArrayList<ByteArray>(count)
            memScoped {
                // Every supported target is 64-bit, so `size_t` is always a ULong here.
                val length = alloc<ULongVar>()
                for (index in 0 until count) {
                    val pointer = typst_kmp_result_blob(result, index.convert(), length.ptr.reinterpret())
                    val size = length.value.toInt()
                    blobs += if (pointer == null || size == 0) {
                        ByteArray(0)
                    } else {
                        pointer.reinterpret<ByteVar>().readBytes(size)
                    }
                }
            }
            return NativeResult(json, blobs)
        } finally {
            typst_kmp_result_free(result)
        }
    }

    actual fun close() {
        val current = handle ?: return
        handle = null
        typst_kmp_engine_free(current)
    }

    private fun alive(): CPointer<cnames.structs.TypstKmpEngine> =
        handle ?: throw TypstNativeException("The native Typst engine is already closed.")
}

/**
 * Pins [this] for the duration of [block], passing a `(pointer, length)` pair.
 *
 * Empty arrays cannot be pinned, so they are handed over as a null pointer with length zero —
 * which is exactly what the C side expects.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.withBuffer(
    block: (CPointer<UByteVar>?, ULong) -> R,
): R = if (isEmpty()) {
    block(null, 0uL)
} else {
    usePinned { pinned -> block(pinned.addressOf(0).reinterpret(), size.convert()) }
}

/** Throws the message the native layer left in [error], releasing it first. */
@OptIn(ExperimentalForeignApi::class)
private fun fail(error: CPointerVar<ByteVar>, fallback: String): Nothing {
    val pointer = error.value
    val message = pointer?.toKString() ?: fallback
    if (pointer != null) typst_kmp_string_free(pointer)
    throw TypstNativeException(message)
}
