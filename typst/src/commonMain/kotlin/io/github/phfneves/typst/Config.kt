package io.github.phfneves.typst

/** Engine-wide settings, fixed for the lifetime of a [Typst] instance. */
public class TypstConfig(
    /**
     * Whether to use the fonts baked into the native binary (DejaVu Sans Mono, Libertinus Serif,
     * New Computer Modern). Turn this off only if the artifact was built without the
     * `embed-fonts` cargo feature — otherwise it just hides fonts that are already paying for
     * their own bytes.
     */
    public val embedDefaultFonts: Boolean = true,
    /** Extra font files registered at startup. Every face in each file is picked up. */
    public val fonts: List<ByteArray> = emptyList(),
    /**
     * Supplies files the document reads but the request did not include.
     *
     * What it returns is kept by the instance, and the same path is not asked for again until
     * [Typst.removeFile] or [Typst.clearFiles] removes it. Serve files that change under the same
     * path, or that depend on the request, through [CompileRequest.files] or
     * [CompileRequest.fileResolver] instead.
     */
    public val fileResolver: FileResolver? = null,
    /** Supplies `.tar.gz` archives for `@namespace/name:version` imports. */
    public val packageResolver: PackageResolver? = null,
    /**
     * An upper bound on the resolve-and-retry rounds of a single compilation.
     *
     * Compilation now carries on for as long as each round resolves something new, which always
     * ends, so there is no depth to tune any more.
     */
    @Deprecated("Resolution no longer needs a bound; leave this unset. It will be removed.")
    public val maxResolveRounds: Int = Int.MAX_VALUE,
    /**
     * Web only: where the WebAssembly module and its glue are served from.
     *
     * Ignored on every other platform, which link or load their native library directly.
     *
     * The browser needs two files — `typst_kmp_wasm.js` and `typst_kmp_wasm_bg.wasm` — and by
     * default looks for them in `typst-kmp/` next to the page. Set this when they are hosted
     * elsewhere, for instance on a CDN; a cross-origin location works as long as it sends CORS
     * headers. A trailing slash is optional.
     */
    public val webAssetBaseUrl: String? = null,
)

/**
 * Supplies a file the compiler asked for.
 *
 * Return `null` for paths you do not serve; the compilation then fails with the path listed in
 * [CompileResult.Failure.unresolved].
 */
public fun interface FileResolver {
    public suspend fun resolve(path: String): ByteArray?
}

/**
 * Supplies the archive of a Typst package: a `.tar.gz`, as published, or a plain `.tar`.
 *
 * Ready-made ones: `DirectoryPackageResolver` reads a local directory (everywhere but the web),
 * [RecordingPackageResolver] records what a compilation imports, and the `typst-kmp-universe`
 * artifact downloads from Typst Universe. [orElse] chains them.
 */
public fun interface PackageResolver {
    public suspend fun resolve(spec: PackageSpec): ByteArray?
}

/** Serves files from an in-memory map. Useful for tests and for bundled templates. */
public fun FileResolver(files: Map<String, ByteArray>): FileResolver =
    FileResolver { path -> files[path] }
