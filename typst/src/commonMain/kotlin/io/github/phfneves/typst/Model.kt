package io.github.phfneves.typst

/**
 * Everything a compilation needs.
 *
 * Files live in an explicit in-memory map rather than on disk: the native compiler has no
 * filesystem access at all, which makes the sandbox airtight and lets the exact same request run
 * on Android, iOS or in a browser. Anything not listed here is asked for through
 * [TypstConfig.fileResolver] and [TypstConfig.packageResolver] while compiling.
 */
public class CompileRequest(
    /** Entry point, as a VFS path. */
    public val main: String = "/main.typ",
    /**
     * Files this compilation sees, keyed by VFS paths such as `/chapters/one.typ`.
     *
     * They belong to this compilation alone: they shadow any instance file at the same path,
     * other compilations running at the same time never see them, and they are gone when
     * [Typst.compile] returns.
     */
    public val files: Map<String, ByteArray> = emptyMap(),
    /** Values reachable from the document through `sys.inputs`. */
    public val inputs: Map<String, String> = emptyMap(),
    /** Artifacts to produce. Asking for several reuses a single layout pass. */
    public val outputs: List<OutputFormat> = listOf(OutputFormat.Pdf()),
    /**
     * The date the document sees through `datetime.today()`. `null` makes `today()` return none,
     * which keeps compilation reproducible.
     */
    public val now: TypstDate? = null,
    /**
     * Supplies files this compilation reads but [files] does not include, such as the rest of a
     * template directory chosen per request.
     *
     * It is asked before [TypstConfig.fileResolver], and what it returns is treated like [files]:
     * visible to this compilation only and gone when it returns. A path it answers `null` for
     * falls through to [TypstConfig.fileResolver].
     */
    public val fileResolver: FileResolver? = null,
) {
    public companion object {
        /** Compiles a single source string, with no other files involved. */
        public fun of(
            source: String,
            inputs: Map<String, String> = emptyMap(),
            outputs: List<OutputFormat> = listOf(OutputFormat.Pdf()),
            now: TypstDate? = null,
        ): CompileRequest = CompileRequest(
            main = MAIN,
            files = mapOf(MAIN to source.encodeToByteArray()),
            inputs = inputs,
            outputs = outputs,
            now = now,
        )

        private const val MAIN = "/main.typ"
    }
}

/** A fixed timestamp handed to the compiler, so that Rust never reads the system clock. */
public data class TypstDate(
    public val year: Int,
    public val month: Int,
    public val day: Int,
    public val hour: Int = 0,
    public val minute: Int = 0,
    public val second: Int = 0,
    /** Minutes east of UTC. */
    public val offsetMinutes: Int = 0,
)

/** An artifact to produce from a compiled document. */
public sealed interface OutputFormat {

    public data class Pdf(
        /** Stable document identifier; `null` lets Typst derive one. */
        public val ident: String? = null,
        public val creator: String? = null,
        public val standards: List<PdfStandard> = emptyList(),
        public val pretty: Boolean = false,
    ) : OutputFormat

    public data class Svg(
        /** `true` emits one document; `false` emits one SVG per page. */
        public val merged: Boolean = false,
        public val pretty: Boolean = false,
    ) : OutputFormat

    public data class Png(
        public val pixelPerPt: Float = 2f,
        /** `true` emits one tall image; `false` emits one PNG per page. */
        public val merged: Boolean = false,
    ) : OutputFormat

    /** The equivalent of `typst query`: pulls structured data back out of the document. */
    public data class Query(
        public val selector: String,
        public val field: String? = null,
        /** Fail unless the selector matches exactly one element. */
        public val one: Boolean = false,
        public val pretty: Boolean = true,
    ) : OutputFormat
}

public enum class PdfStandard(internal val wire: String) {
    V1_4("1.4"),
    V1_5("1.5"),
    V1_6("1.6"),
    V1_7("1.7"),
    V2_0("2.0"),
    A_2B("a-2b"),
    A_3B("a-3b"),
}

/** A produced artifact, in the same order as the [CompileRequest.outputs] that asked for it. */
public sealed interface Output {

    public class Pdf(public val bytes: ByteArray) : Output

    /** One entry per page, or a single entry when [OutputFormat.Svg.merged] was set. */
    public class Svg(public val pages: List<String>) : Output

    /** One entry per page, or a single entry when [OutputFormat.Png.merged] was set. */
    public class Png(public val pages: List<ByteArray>) : Output

    /** Raw JSON produced by the query. */
    public class Query(public val json: String) : Output
}

public sealed interface CompileResult {

    public val warnings: List<Diagnostic>

    public class Success(
        public val outputs: List<Output>,
        override val warnings: List<Diagnostic>,
    ) : CompileResult

    public class Failure(
        public val errors: List<Diagnostic>,
        override val warnings: List<Diagnostic>,
        /**
         * Files and packages the document asked for that neither the request nor the resolvers
         * could supply. Non-empty here usually means a missing resolver or a typo in an import.
         */
        public val unresolved: List<Unresolved>,
    ) : CompileResult

    /** The outputs on success, or a [TypstCompilationException] describing every error. */
    public fun getOrThrow(): List<Output> = when (this) {
        is Success -> outputs
        is Failure -> throw TypstCompilationException(errors, unresolved)
    }
}

/**
 * The PDF this compilation produced.
 *
 * @throws IllegalStateException if the request did not ask for exactly one PDF.
 */
public val CompileResult.Success.pdf: ByteArray
    get() = outputs.filterIsInstance<Output.Pdf>().singleOrNull()?.bytes
        ?: error("Expected exactly one PDF among the outputs; request one with OutputFormat.Pdf.")

/** Convenience accessor for the common single-PDF case; `null` on failure. */
public val CompileResult.pdfBytes: ByteArray?
    get() = (this as? CompileResult.Success)
        ?.outputs
        ?.filterIsInstance<Output.Pdf>()
        ?.firstOrNull()
        ?.bytes

public sealed interface Unresolved {
    public data class File(public val path: String) : Unresolved
    public data class Package(public val spec: PackageSpec) : Unresolved
}

public data class PackageSpec(
    public val namespace: String,
    public val name: String,
    public val version: String,
) {
    /** The `@namespace/name:version` form used in `#import` and on the wire. */
    override fun toString(): String = "@$namespace/$name:$version"
}

public data class Diagnostic(
    public val severity: Severity,
    public val message: String,
    /** VFS path the diagnostic points at, when it has a source location. */
    public val path: String?,
    /** Byte offset of the start of the span. */
    public val start: Int?,
    public val end: Int?,
    /** 1-based line number. */
    public val line: Int?,
    /** 1-based column number. */
    public val column: Int?,
    public val hints: List<String>,
    public val trace: List<TracePoint>,
    /**
     * The diagnostic laid out as the `typst` CLI prints it, without colour: the message, the
     * location, the source line with the span underlined, then the hints and the trace.
     */
    public val rendered: String = "",
) {
    override fun toString(): String = buildString {
        append(severity.name.lowercase()).append(": ").append(message)
        if (path != null) {
            append(" (").append(path)
            if (line != null) append(':').append(line)
            if (column != null) append(':').append(column)
            append(')')
        }
    }
}

public data class TracePoint(
    public val message: String,
    public val path: String?,
    public val line: Int?,
    public val column: Int?,
)

public enum class Severity { ERROR, WARNING }

/** A file the instance keeps, as [Typst.listFiles] reports it. */
public data class VfsEntry(
    /** VFS path, in the same form as [CompileRequest.files]. */
    public val path: String,
    /** Size in bytes. */
    public val size: Long,
)
