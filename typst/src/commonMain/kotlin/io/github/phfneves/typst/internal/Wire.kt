package io.github.phfneves.typst.internal

import io.github.phfneves.typst.CompileRequest
import io.github.phfneves.typst.Diagnostic
import io.github.phfneves.typst.OutputFormat
import io.github.phfneves.typst.Severity
import io.github.phfneves.typst.TracePoint
import io.github.phfneves.typst.TypstConfig
import io.github.phfneves.typst.TypstException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON protocol shared with `typst-kmp-core`.
 *
 * These declarations mirror the serde types in `rust/typst-kmp-core/src/protocol.rs` one for one;
 * change them together.
 */

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// --- outbound ---------------------------------------------------------------------------------

@Serializable
internal class WireEngineConfig(val embedDefaultFonts: Boolean)

@Serializable
internal class WireRequest(
    val main: String,
    val inputs: Map<String, String>,
    val outputs: List<WireOutputSpec>,
    val now: WireDate? = null,
)

@Serializable
internal class WireDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val offsetMinutes: Int,
)

@Serializable
internal sealed class WireOutputSpec {

    @Serializable
    @SerialName("pdf")
    internal class Pdf(
        val ident: String? = null,
        val creator: String? = null,
        val standards: List<String> = emptyList(),
        val pretty: Boolean = false,
    ) : WireOutputSpec()

    @Serializable
    @SerialName("svg")
    internal class Svg(val merged: Boolean, val pretty: Boolean) : WireOutputSpec()

    @Serializable
    @SerialName("png")
    internal class Png(val pixelPerPt: Float, val merged: Boolean) : WireOutputSpec()

    @Serializable
    @SerialName("query")
    internal class Query(
        val selector: String,
        val field: String? = null,
        val one: Boolean = false,
        val pretty: Boolean = true,
    ) : WireOutputSpec()
}

// --- inbound ----------------------------------------------------------------------------------

@Serializable
internal class WireCompileResponse(
    val ok: Boolean,
    val outputs: List<WireOutputMeta> = emptyList(),
    val diagnostics: List<WireDiagnostic> = emptyList(),
    val missing: List<WireMissing> = emptyList(),
)

@Serializable
internal class WireOutputMeta(
    val kind: String,
    val blobStart: Int,
    val blobCount: Int,
)

@Serializable
internal class WireDiagnostic(
    val severity: String,
    val message: String,
    val path: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val hints: List<String> = emptyList(),
    val trace: List<WireTracePoint> = emptyList(),
)

@Serializable
internal class WireTracePoint(
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed class WireMissing {

    @Serializable
    @SerialName("file")
    internal class File(val path: String) : WireMissing()

    @Serializable
    @SerialName("package")
    internal class Package(
        val namespace: String,
        val name: String,
        val version: String,
    ) : WireMissing()
}

// --- conversions ------------------------------------------------------------------------------

internal fun encodeConfig(config: TypstConfig): String =
    json.encodeToString(WireEngineConfig(config.embedDefaultFonts))

internal fun encodeRequest(request: CompileRequest): String = json.encodeToString(
    WireRequest(
        main = request.main,
        inputs = request.inputs,
        outputs = request.outputs.map { it.toWire() },
        now = request.now?.let {
            WireDate(it.year, it.month, it.day, it.hour, it.minute, it.second, it.offsetMinutes)
        },
    ),
)

internal fun decodeResponse(text: String): WireCompileResponse = try {
    json.decodeFromString<WireCompileResponse>(text)
} catch (error: SerializationException) {
    throw TypstException("Native layer returned a malformed response: $text", error)
}

private fun OutputFormat.toWire(): WireOutputSpec = when (this) {
    is OutputFormat.Pdf -> WireOutputSpec.Pdf(ident, creator, standards.map { it.wire }, pretty)
    is OutputFormat.Svg -> WireOutputSpec.Svg(merged, pretty)
    is OutputFormat.Png -> WireOutputSpec.Png(pixelPerPt, merged)
    is OutputFormat.Query -> WireOutputSpec.Query(selector, field, one, pretty)
}

internal fun WireDiagnostic.toDiagnostic(): Diagnostic = Diagnostic(
    severity = if (severity == "warning") Severity.WARNING else Severity.ERROR,
    message = message,
    path = path,
    start = start,
    end = end,
    line = line,
    column = column,
    hints = hints,
    trace = trace.map { TracePoint(it.message, it.path, it.line, it.column) },
)
