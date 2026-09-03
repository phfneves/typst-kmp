package io.github.phfneves.typst

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
}
