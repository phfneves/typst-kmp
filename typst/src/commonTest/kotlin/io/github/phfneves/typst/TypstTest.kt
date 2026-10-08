package io.github.phfneves.typst

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypstTest {

    @Test
    fun compilesASimpleDocumentToPdf() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(CompileRequest.of("= Olá\nMundo"))

            val pdf = assertIs<CompileResult.Success>(result)
                .outputs
                .filterIsInstance<Output.Pdf>()
                .single()
            assertTrue(pdf.bytes.decodeToString(0, 5).startsWith("%PDF-"), "not a PDF")
        }
    }

    @Test
    fun producesSvgAndPngForEveryPage() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest.of(
                    source = "#set page(width: 5cm, height: 5cm)\nA\n#pagebreak()\nB",
                    outputs = listOf(OutputFormat.Svg(), OutputFormat.Png(pixelPerPt = 1f)),
                ),
            )

            val success = assertIs<CompileResult.Success>(result)
            val svg = success.outputs.filterIsInstance<Output.Svg>().single()
            val png = success.outputs.filterIsInstance<Output.Png>().single()
            assertEquals(2, svg.pages.size)
            assertEquals(2, png.pages.size)
            assertTrue(svg.pages.first().startsWith("<svg"), "not an SVG")
        }
    }

    @Test
    fun reportsCompilationErrorsWithALocation() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(CompileRequest.of("#panic(\"boom\")"))

            val failure = assertIs<CompileResult.Failure>(result)
            val error = failure.errors.single()
            assertEquals(Severity.ERROR, error.severity)
            assertEquals("/main.typ", error.path)
            assertEquals(1, error.line)
        }
    }

    @Test
    fun resolvesAMissingFileThroughTheResolver() = runTest {
        val helper = "#let greet() = [Olá do helper]".encodeToByteArray()
        val config = TypstConfig(
            fileResolver = FileResolver(mapOf("/helpers.typ" to helper)),
        )

        Typst.create(config).use { typst ->
            val result = typst.compile(
                CompileRequest.of("#import \"/helpers.typ\": greet\n#greet()"),
            )

            assertIs<CompileResult.Success>(result)
        }
    }

    @Test
    fun listsWhatItCouldNotResolve() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest.of("#import \"/missing.typ\": anything"),
            )

            val failure = assertIs<CompileResult.Failure>(result)
            assertContains(failure.unresolved, Unresolved.File("/missing.typ"))
        }
    }

    @Test
    fun asksThePackageResolverForPreviewImports() = runTest {
        var requested: PackageSpec? = null
        val config = TypstConfig(
            packageResolver = { spec ->
                requested = spec
                null
            },
        )

        Typst.create(config).use { typst ->
            val result = typst.compile(
                CompileRequest.of("#import \"@preview/example:0.1.0\": *"),
            )

            assertIs<CompileResult.Failure>(result)
            assertEquals(PackageSpec("preview", "example", "0.1.0"), requested)
        }
    }

    @Test
    fun exposesInputsThroughSysInputs() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest.of(
                    source = "#sys.inputs.at(\"name\")",
                    inputs = mapOf("name" to "Pedro"),
                ),
            )

            assertIs<CompileResult.Success>(result)
        }
    }

    @Test
    fun queriesTheDocument() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest.of(
                    source = "#metadata(\"answer\") <marker>",
                    outputs = listOf(OutputFormat.Query("<marker>", field = "value")),
                ),
            )

            val query = assertIs<CompileResult.Success>(result)
                .outputs
                .filterIsInstance<Output.Query>()
                .single()
            assertContains(query.json, "answer")
        }
    }

    @Test
    fun compilingAfterCloseFails() = runTest {
        val typst = Typst.create()
        typst.close()

        val error = kotlin.runCatching { typst.compile(CompileRequest.of("hello")) }
        assertTrue(error.isFailure, "expected compiling on a closed engine to fail")
    }

    @Test
    fun concurrentCompilationsSharingAPathDoNotSeeEachOthersFiles() = runTest {
        Typst.create().use { typst ->
            val values = coroutineScope {
                (1..6).map { index ->
                    async {
                        val result = typst.compile(
                            CompileRequest(
                                files = mapOf(
                                    "/main.typ" to "#metadata(\"v$index\") <v>".encodeToByteArray(),
                                ),
                                outputs = listOf(OutputFormat.Query("<v>", field = "value", one = true)),
                            ),
                        )
                        assertIs<CompileResult.Success>(result)
                            .outputs
                            .filterIsInstance<Output.Query>()
                            .single()
                            .json
                    }
                }.awaitAll()
            }

            assertEquals((1..6).map { "\"v$it\"" }, values)
        }
    }

    @Test
    fun keepsResolvedFilesUntilTheyAreRemoved() = runTest {
        var calls = 0
        val config = TypstConfig(
            fileResolver = { path ->
                calls++
                if (path == "/helpers.typ") "#let x = 1".encodeToByteArray() else null
            },
        )

        Typst.create(config).use { typst ->
            val request = CompileRequest.of("#import \"/helpers.typ\": x\n#x")
            assertIs<CompileResult.Success>(typst.compile(request))
            assertIs<CompileResult.Success>(typst.compile(request))
            assertEquals(1, calls, "a resolved file should be kept")

            assertTrue(typst.removeFile("/helpers.typ"))
            assertFalse(typst.removeFile("/helpers.typ"))

            assertIs<CompileResult.Success>(typst.compile(request))
            assertEquals(2, calls, "a removed file should be resolved again")
        }
    }

    @Test
    fun clearFilesRemovesRequestAndResolvedFiles() = runTest {
        val config = TypstConfig(
            fileResolver = FileResolver(mapOf("/helpers.typ" to "#let x = 1".encodeToByteArray())),
        )

        Typst.create(config).use { typst ->
            assertIs<CompileResult.Success>(
                typst.compile(CompileRequest.of("#import \"/helpers.typ\": x\n#x")),
            )

            assertEquals(2, typst.clearFiles())
            assertEquals(0, typst.clearFiles())

            val failure = assertIs<CompileResult.Failure>(typst.compile(CompileRequest()))
            assertContains(failure.unresolved, Unresolved.File("/main.typ"))
        }
    }

    @Test
    fun clearPackagesWithNothingLoadedRemovesNothing() = runTest {
        Typst.create().use { typst ->
            assertEquals(0, typst.clearPackages())
        }
    }

    @Test
    fun closingDuringACompilationLetsItFinish() = runTest {
        lateinit var typst: Typst
        val config = TypstConfig(
            fileResolver = { path ->
                // Closes the instance while the compilation is in the middle of using it.
                typst.close()
                if (path == "/helpers.typ") "#let x = 1".encodeToByteArray() else null
            },
        )
        typst = Typst.create(config)

        val result = typst.compile(CompileRequest.of("#import \"/helpers.typ\": x\n#x"))

        assertIs<CompileResult.Success>(result)
        assertFailsWith<IllegalStateException> { typst.compile(CompileRequest.of("hello")) }
    }
}
