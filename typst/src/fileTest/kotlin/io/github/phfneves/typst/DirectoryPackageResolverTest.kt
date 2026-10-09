package io.github.phfneves.typst

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DirectoryPackageResolverTest {

    private val root = Path(SystemTemporaryDirectory, "typst-kmp-packages-${Random.nextLong().toULong()}")

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

    private fun write(relative: String, bytes: ByteArray) {
        val path = Path(root.toString(), *relative.split('/').toTypedArray())
        SystemFileSystem.createDirectories(path.parent!!)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }

    @Test
    fun servesAnArchiveAsItIs() = runTest {
        val archive = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
        write("preview/cetz-0.4.2.tar.gz", archive)

        val resolved = DirectoryPackageResolver(root.toString())
            .resolve(PackageSpec("preview", "cetz", "0.4.2"))

        assertContentEquals(archive, resolved)
    }

    @Test
    fun returnsNullForAPackageItDoesNotHave() = runTest {
        SystemFileSystem.createDirectories(root)
        assertNull(DirectoryPackageResolver(root.toString()).resolve(PackageSpec("preview", "x", "1.0.0")))
    }

    @Test
    fun compilesAgainstAnUnpackedPackage() = runTest {
        write(
            "local/greet/0.1.0/typst.toml",
            """
            [package]
            name = "greet"
            version = "0.1.0"
            entrypoint = "src/lib.typ"
            """.trimIndent().encodeToByteArray(),
        )
        write("local/greet/0.1.0/src/lib.typ", "#import \"deep.typ\": name\n#let hello = [Olá, #name]".encodeToByteArray())
        // Deeper than the 100 bytes a plain tar name holds, so the ustar prefix is exercised.
        val deep = "src/" + "nested-directory/".repeat(6) + "deep.typ"
        write("local/greet/0.1.0/$deep", "#let name = \"mundo\"".encodeToByteArray())
        write(
            "local/greet/0.1.0/src/deep.typ",
            "#import \"${deep.removePrefix("src/")}\": name".encodeToByteArray(),
        )

        val config = TypstConfig(packageResolver = DirectoryPackageResolver(root.toString()))
        Typst.create(config).use { typst ->
            val result = typst.compile(
                CompileRequest.of("#import \"@local/greet:0.1.0\": hello\n#hello"),
            )
            assertIs<CompileResult.Success>(result, (result as? CompileResult.Failure)?.errors.toString())
        }
    }
}
