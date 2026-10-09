package io.github.phfneves.typst.universe

import io.github.phfneves.typst.DirectoryPackageResolver
import io.github.phfneves.typst.PackageSpec
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectoryPackageCacheTest {

    private val root = Path(SystemTemporaryDirectory, "typst-kmp-cache-${Random.nextLong().toULong()}")
    private val cetz = PackageSpec("preview", "cetz", "0.4.2")

    @AfterTest
    fun cleanUp() {
        fun delete(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                SystemFileSystem.list(path).forEach(::delete)
            }
            SystemFileSystem.delete(path, mustExist = false)
        }
        delete(root)
    }

    @Test
    fun keepsArchivesWhereTheDirectoryResolverFindsThem() = runTest {
        val archive = byteArrayOf(0x1f, 0x8b.toByte(), 1)
        DirectoryPackageCache(root.toString()).put(cetz, archive)

        assertContentEquals(archive, DirectoryPackageCache(root.toString()).get(cetz))
        assertContentEquals(archive, DirectoryPackageResolver(root.toString()).resolve(cetz))
        // Nothing but the archive is left behind.
        assertEquals(listOf("cetz-0.4.2.tar.gz"), SystemFileSystem.list(Path(root, "preview")).map { it.name })
    }

    @Test
    fun returnsNullForAnArchiveItDoesNotHave() = runTest {
        assertNull(DirectoryPackageCache(root.toString()).get(cetz))
    }
}
