package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException
import io.github.phfneves.typst.cinterop.typst_kmp_compile
import io.github.phfneves.typst.cinterop.typst_kmp_engine_add_font
import io.github.phfneves.typst.cinterop.typst_kmp_engine_free
import io.github.phfneves.typst.cinterop.typst_kmp_engine_inspect
import io.github.phfneves.typst.cinterop.typst_kmp_engine_new
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_clear_files
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_clear_packages
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_put
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_put_package
import io.github.phfneves.typst.cinterop.typst_kmp_engine_vfs_remove
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
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.pin
import kotlinx.cinterop.set
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeEngine private constructor(configJson: String) {

    private var handle: CPointer<cnames.structs.TypstKmpEngine>? = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        typst_kmp_engine_new(configJson, error.ptr)
            ?: fail(error, "Failed to create the native Typst engine.")
    }

    actual suspend fun addFont(bytes: ByteArray): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val added = bytes.withBuffer { pointer, length ->
            typst_kmp_engine_add_font(alive(), pointer, length, error.ptr)
        }
        if (added < 0) fail(error, "Failed to register the font.")
        added
    }

    actual suspend fun vfsPut(path: String, bytes: ByteArray): Unit = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val status = bytes.withBuffer { pointer, length ->
            typst_kmp_engine_vfs_put(alive(), path, pointer, length, error.ptr)
        }
        if (status != 0) fail(error, "Failed to write $path into the virtual file system.")
    }

    actual suspend fun vfsPutPackage(spec: String, archive: ByteArray): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val count = archive.withBuffer { pointer, length ->
            typst_kmp_engine_vfs_put_package(alive(), spec, pointer, length, error.ptr)
        }
        if (count < 0) fail(error, "Failed to unpack the package $spec.")
        count
    }

    actual suspend fun vfsRemove(path: String): Boolean = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val status = typst_kmp_engine_vfs_remove(alive(), path, error.ptr)
        if (status < 0) fail(error, "Failed to remove $path from the virtual file system.")
        status == 1
    }

    actual suspend fun vfsClearFiles(): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val count = typst_kmp_engine_vfs_clear_files(alive(), error.ptr)
        if (count < 0) fail(error, "Failed to clear the virtual file system.")
        count
    }

    actual suspend fun vfsClearPackages(): Int = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val count = typst_kmp_engine_vfs_clear_packages(alive(), error.ptr)
        if (count < 0) fail(error, "Failed to clear the packages.")
        count
    }

    actual suspend fun inspect(): String = memScoped {
        val error = alloc<CPointerVar<ByteVar>>()
        val pointer = typst_kmp_engine_inspect(alive(), error.ptr)
            ?: fail(error, "Failed to inspect the native Typst engine.")
        try {
            pointer.toKString()
        } finally {
            typst_kmp_string_free(pointer)
        }
    }

    actual suspend fun compile(requestJson: String, files: List<ByteArray>): NativeResult {
        val result = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            val pointers = allocArray<CPointerVar<UByteVar>>(files.size)
            val lengths = allocArray<ULongVar>(files.size)
            // Pin every file for the duration of the call; the engine copies what it keeps.
            val pinned = files.map { if (it.isEmpty()) null else it.pin() }
            try {
                pinned.forEachIndexed { index, pin ->
                    pointers[index] = pin?.addressOf(0)?.reinterpret()
                    lengths[index] = files[index].size.convert()
                }
                typst_kmp_compile(
                    alive(),
                    requestJson,
                    pointers.reinterpret(),
                    lengths.reinterpret(),
                    files.size.convert(),
                    error.ptr,
                ) ?: fail(error, "The native Typst engine failed to compile.")
            } finally {
                pinned.forEach { it?.unpin() }
            }
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

    internal companion object {
        fun open(configJson: String): NativeEngine = NativeEngine(configJson)
    }
}

/** Nothing here is asynchronous; the archive is linked into this binary. */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun createNativeEngine(options: EngineOptions): NativeEngine =
    NativeEngine.open(options.configJson)

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
