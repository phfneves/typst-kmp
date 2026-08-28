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
    /** Supplies files the document imports but the request did not include. */
    public val fileResolver: FileResolver? = null,
    /** Supplies `.tar.gz` archives for `@namespace/name:version` imports. */
    public val packageResolver: PackageResolver? = null,
    /**
     * How many resolve-and-retry rounds a single compilation may take.
     *
     * Each round satisfies every miss the compiler reported at once, so this bounds the *depth*
     * of the dependency chain, not the number of files.
     */
    public val maxResolveRounds: Int = 8,
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

/** Supplies the `.tar.gz` archive of a Typst package. */
public fun interface PackageResolver {
    public suspend fun resolve(spec: PackageSpec): ByteArray?
}

/** Serves files from an in-memory map. Useful for tests and for bundled templates. */
public fun FileResolver(files: Map<String, ByteArray>): FileResolver =
    FileResolver { path -> files[path] }
