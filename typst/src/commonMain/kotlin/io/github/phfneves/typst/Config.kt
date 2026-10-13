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
    /**
     * Font files, or directories searched for them, to use besides [fonts].
     *
     * Unlike [fonts], these are not read up front. Creating the instance reads just enough of each
     * file to know which faces it holds, and a face is mapped into memory from disk the first time
     * a document uses it, so a large collection costs next to nothing until it is needed.
     * Directories are searched recursively for `.ttf`, `.otf`, `.ttc` and `.otc` files.
     *
     * Creating the instance fails if a path does not exist, or names a file that is not a font.
     * Not available in the browser, which has no file system: there, any path fails.
     *
     * On Android, APK assets are not files; copy them to `Context.filesDir` once and name that.
     */
    public val fontPaths: List<String> = emptyList(),
    /**
     * Whether to make the platform's installed fonts available too, loaded on first use like
     * [fontPaths]: `/system/fonts` on Android; the `Fonts` folders of the system, the machine and
     * the user on macOS and Windows; `/usr/share/fonts` and the user's font folders on Linux.
     *
     * On iOS the app sandbox may hide `/System/Library/Fonts`, in which case this finds nothing;
     * bundle the fonts a document needs instead. In the browser it finds nothing.
     */
    public val includeSystemFonts: Boolean = false,
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
