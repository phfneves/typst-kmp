package io.github.phfneves.typst

import io.github.phfneves.typst.fixtures.TypstFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The full pipeline, on every platform: a real multi-file `.typ` document from
 * `src/commonTest/typst` goes in, a PDF a human can open comes out.
 *
 * The PDF is written to [testOutputDirectory] so it can actually be looked at, but no assertion
 * depends on that — a platform with nowhere to write still verifies everything else.
 */
class EndToEndReportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixtureRequest(vararg outputs: OutputFormat) = CompileRequest(
        main = "/report.typ",
        files = TypstFixtures.files.mapValues { (_, source) -> source.encodeToByteArray() },
        inputs = mapOf("author" to "suíte typst-kmp", "build" to platformName),
        outputs = outputs.toList(),
        now = TypstDate(year = 2026, month = 8, day = 24),
    )

    @Test
    fun compilesTheReportFixtureToEveryFormatAndWritesThePdf() = runTest {
        Typst.create().use { typst ->
            val result = typst.compile(
                fixtureRequest(
                    OutputFormat.Pdf(ident = "typst-kmp-e2e", creator = "typst-kmp tests"),
                    OutputFormat.Svg(),
                    OutputFormat.Png(pixelPerPt = 2f),
                    OutputFormat.Query("<marker>", field = "value"),
                ),
            )

            val success = assertIs<CompileResult.Success>(
                result,
                "compilation failed: ${(result as? CompileResult.Failure)?.errors}",
            )
            assertEquals(4, success.outputs.size)

            val pdf = success.outputs.filterIsInstance<Output.Pdf>().single().bytes
            val svg = success.outputs.filterIsInstance<Output.Svg>().single()
            val png = success.outputs.filterIsInstance<Output.Png>().single()
            val query = success.outputs.filterIsInstance<Output.Query>().single()

            // A real document, not an empty page: the fixture is two pages of text, maths and a
            // table, so anything much smaller means the content silently went missing.
            assertTrue(pdf.size > 20_000, "PDF is suspiciously small: ${pdf.size} bytes")
            assertEquals("%PDF-", pdf.decodeToString(0, 5), "not a PDF")
            assertContains(
                pdf.decodeToString(pdf.size - 32, pdf.size),
                "%%EOF",
                message = "PDF is truncated",
            )

            // The fixture has an explicit #pagebreak(), so every per-page exporter must emit two.
            assertEquals(2, svg.pages.size, "expected one SVG per page")
            assertEquals(2, png.pages.size, "expected one PNG per page")
            svg.pages.forEach { page -> assertTrue(page.startsWith("<svg"), "not an SVG") }
            png.pages.forEach { page ->
                assertEquals("PNG", page.decodeToString(1, 4), "not a PNG")
                assertTrue(page.size > 5_000, "PNG page is suspiciously small: ${page.size} bytes")
            }

            // The query walks the compiled document, which only works if layout really ran.
            val marker = json.parseToJsonElement(query.json)
            assertEquals("typst-kmp", (marker as JsonArray).single().jsonPrimitive.content)

            val written = writeTestArtifact("typst-kmp-report-$platformName.pdf", pdf)
            println(
                if (written != null) "End-to-end PDF written to $written (${pdf.size} bytes)"
                else "End-to-end PDF verified in memory (${pdf.size} bytes); no output directory " +
                    "configured on $platformName",
            )
        }
    }

    @Test
    fun compilingTheSameFixtureTwiceIsDeterministic() = runTest {
        Typst.create().use { typst ->
            val first = typst.compile(fixtureRequest(OutputFormat.Pdf(ident = "fixed")))
            val second = typst.compile(fixtureRequest(OutputFormat.Pdf(ident = "fixed")))

            val a = assertIs<CompileResult.Success>(first).outputs.single() as Output.Pdf
            val b = assertIs<CompileResult.Success>(second).outputs.single() as Output.Pdf
            // A pinned `ident` and the supplied date are what make this reproducible; the engine
            // never reads a clock of its own.
            assertTrue(a.bytes.contentEquals(b.bytes), "two identical compilations differed")
        }
    }

    @Test
    fun nonAsciiSurvivesTheRoundTripThroughRust() = runTest {
        val text = "Olá — açúcar, ñandú, 日本語, ✅"

        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest.of(
                    source = "#metadata(sys.inputs.at(\"text\")) <echo>",
                    inputs = mapOf("text" to text),
                    outputs = listOf(OutputFormat.Query("<echo>", field = "value")),
                ),
            )

            val query = assertIs<CompileResult.Success>(result)
                .outputs
                .filterIsInstance<Output.Query>()
                .single()
            val decoded = (json.parseToJsonElement(query.json) as JsonArray).single()
            assertEquals(text, decoded.jsonPrimitive.content)
        }
    }

    @Test
    fun reportsAnErrorInsideAnImportedFileAgainstThatFile() = runTest {
        val broken = TypstFixtures.files.toMutableMap()
        broken["/helpers.typ"] = "#let callout(body) = body\n#let sample-table = undefined-name\n"

        Typst.create().use { typst ->
            val result = typst.compile(
                CompileRequest(
                    main = "/report.typ",
                    files = broken.mapValues { (_, source) -> source.encodeToByteArray() },
                    outputs = listOf(OutputFormat.Pdf()),
                ),
            )

            val failure = assertIs<CompileResult.Failure>(result)
            val error = failure.errors.first()
            // The diagnostic must point at the imported file, not at the entry point — that only
            // works if file identity survives the FFI boundary intact.
            assertEquals("/helpers.typ", error.path)
            assertEquals(2, error.line)
        }
    }
}
