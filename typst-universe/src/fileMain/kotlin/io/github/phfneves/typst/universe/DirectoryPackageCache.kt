package io.github.phfneves.typst.universe

import io.github.phfneves.typst.PackageSpec
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Keeps archives on disk, as `<root>/<namespace>/<name>-<version>.tar.gz`.
 *
 * That is one of the layouts `DirectoryPackageResolver` reads, so a directory filled here — say
 * by compiling every template once at build time — serves the same packages offline later.
 *
 * Each archive is written to a temporary file and moved into place, so a reader never sees half
 * of one.
 */
public class DirectoryPackageCache(private val root: String) : PackageCache {

    override suspend fun get(spec: PackageSpec): ByteArray? = withContext(Dispatchers.IO) {
        val path = pathOf(spec)
        if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) return@withContext null
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    }

    override suspend fun put(spec: PackageSpec, archive: ByteArray) {
        withContext(Dispatchers.IO) {
            val path = pathOf(spec)
            val directory = path.parent!!
            SystemFileSystem.createDirectories(directory)
            val partial = Path(directory, ".${path.name}.${Random.nextLong().toULong()}.part")
            try {
                SystemFileSystem.sink(partial).buffered().use { it.write(archive) }
                SystemFileSystem.atomicMove(partial, path)
            } finally {
                SystemFileSystem.delete(partial, mustExist = false)
            }
        }
    }

    private fun pathOf(spec: PackageSpec): Path =
        Path(root, spec.namespace, "${spec.name}-${spec.version}.tar.gz")
}
