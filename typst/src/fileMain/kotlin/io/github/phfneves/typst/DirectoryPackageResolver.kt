package io.github.phfneves.typst

import io.github.phfneves.typst.internal.TarWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Serves packages from a directory, for templates that must compile offline.
 *
 * Each package is looked for in two layouts under [root], in this order:
 *
 * * `<namespace>/<name>-<version>.tar.gz`, the archive exactly as Typst Universe publishes it;
 * * `<namespace>/<name>/<version>/`, the package unpacked. That is the layout of Typst's own
 *   package directories, so [root] may point at the `typst/packages` folder of a machine that
 *   has the `typst` CLI, or at a copy of it checked into a project.
 *
 * Returns `null` for a package found in neither. On Android, [root] has to be a real directory,
 * such as one under `Context.filesDir`; APK assets are not files.
 */
public class DirectoryPackageResolver(private val root: String) : PackageResolver {

    override suspend fun resolve(spec: PackageSpec): ByteArray? = withContext(Dispatchers.IO) {
        val namespace = Path(root, spec.namespace)
        val archive = Path(namespace, "${spec.name}-${spec.version}.tar.gz")
        val unpacked = Path(namespace, spec.name, spec.version)
        when {
            SystemFileSystem.metadataOrNull(archive)?.isRegularFile == true -> readFile(archive)
            SystemFileSystem.metadataOrNull(unpacked)?.isDirectory == true -> tarDirectory(unpacked)
            else -> null
        }
    }
}

private fun readFile(path: Path): ByteArray =
    SystemFileSystem.source(path).buffered().use { it.readByteArray() }

/** Packs every file under [directory] into an uncompressed tar, paths relative to it. */
private fun tarDirectory(directory: Path): ByteArray {
    val tar = TarWriter()
    fun walk(dir: Path, prefix: String) {
        SystemFileSystem.list(dir).sortedBy { it.name }.forEach { child ->
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val metadata = SystemFileSystem.metadataOrNull(child) ?: return@forEach
            when {
                metadata.isDirectory -> walk(child, relative)
                metadata.isRegularFile -> tar.add(relative, readFile(child))
            }
        }
    }
    walk(directory, "")
    return tar.finish()
}
