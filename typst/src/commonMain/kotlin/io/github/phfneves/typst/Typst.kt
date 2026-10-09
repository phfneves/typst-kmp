package io.github.phfneves.typst

import io.github.phfneves.typst.internal.EngineOptions
import io.github.phfneves.typst.internal.NativeEngine
import io.github.phfneves.typst.internal.WireCompileResponse
import io.github.phfneves.typst.internal.WireMissing
import io.github.phfneves.typst.internal.createNativeEngine
import io.github.phfneves.typst.internal.decodeInspection
import io.github.phfneves.typst.internal.decodeResponse
import io.github.phfneves.typst.internal.encodeConfig
import io.github.phfneves.typst.internal.encodeRequest
import io.github.phfneves.typst.internal.toDiagnostic
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A Typst compiler instance.
 *
 * Create one with [create], reuse it, and [close] it when done. Creating one loads every font, so
 * a long-lived instance shared by the whole application is the intended use.
 *
 * ## Sharing
 *
 * An instance is safe to share between coroutines and threads, and compilations on it run in
 * parallel: each one sees its own [CompileRequest.files] and nothing of another's, even when they
 * use the same paths. Calls that change the instance — [addFont], [removeFile], [clearFiles],
 * [clearPackages], and storing what [TypstConfig.fileResolver] or [TypstConfig.packageResolver]
 * return — wait for the compilations in progress and run alone.
 *
 * In the browser the engine is a single Web Worker, so compilations there still queue.
 *
 * Resolvers run outside any lock and may call back into the instance.
 *
 * ## The virtual file system
 *
 * The compiler sees two layers of in-memory files:
 *
 * * **The request's own files**, [CompileRequest.files] plus whatever [CompileRequest.fileResolver]
 *   returns. They exist for that compilation only and are gone when it returns. Pass data that
 *   changes, such as a JSON payload or a template tied to one request, this way.
 * * **The instance's files**, owned by this instance and kept from one compilation to the next.
 *   A file fetched through [TypstConfig.fileResolver] is kept, and the resolver is not asked for
 *   that path again; that suits assets that never change under a given path. Packages fetched
 *   through [TypstConfig.packageResolver] are kept too — a published version never changes, so
 *   that is a cache, not a hazard.
 *
 * A request's file shadows an instance file at the same path. Nothing is removed from the
 * instance on its own; [removeFile] and [clearFiles] do that. Fonts and packages survive both.
 *
 * ## Cancellation
 *
 * Cancelling the calling coroutine stops [compile] between resolution rounds, but a round already
 * running in native code finishes first: the Typst compiler has no way to be interrupted.
 *
 * ```kotlin
 * Typst.create().use { typst ->
 *     val pdf = typst.compilePdf(CompileRequest.of("= Olá\nMundo"))
 * }
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
public class Typst private constructor(
    private val engine: NativeEngine,
    private val config: TypstConfig,
) : AutoCloseable {

    /** Serialises every change to the instance; see "Sharing" above. */
    private val mutex = Mutex()

    /** Operations that have started and not yet returned; see [close]. */
    private val active = AtomicInt(0)

    private val released = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /**
     * Compiles [request], resolving missing files and packages as it goes.
     *
     * The native compiler cannot perform I/O, so a missing dependency comes back as a structured
     * miss rather than an error. This method fetches those through the resolvers and compiles
     * again, for as long as each round supplies something new. Every file a document imports at
     * the same depth arrives in one round, and Typst's caches carry the work already done from
     * one round to the next, so a deep import chain costs little more than a shallow one.
     */
    public suspend fun compile(request: CompileRequest): CompileResult = tracked {
        compileTracked(request)
    }

    /**
     * Compiles [request] and returns its PDF, for the common case of wanting nothing else.
     *
     * @throws TypstCompilationException if the document did not compile.
     * @throws IllegalStateException if [request] did not ask for exactly one PDF.
     */
    public suspend fun compilePdf(request: CompileRequest): ByteArray =
        when (val result = compile(request)) {
            is CompileResult.Success -> result.pdf
            is CompileResult.Failure -> throw TypstCompilationException(result.errors, result.unresolved)
        }

    @Suppress("DEPRECATION")
    private suspend fun compileTracked(request: CompileRequest): CompileResult {
        val overlay = LinkedHashMap(request.files)
        val attempted = mutableSetOf<String>()

        var lastResponse: WireCompileResponse? = null
        var rounds = 0
        while (rounds++ < config.maxResolveRounds.coerceAtLeast(1)) {
            currentCoroutineContext().ensureActive()
            val paths = overlay.keys.toList()
            val requestJson = encodeRequest(request, paths)
            val native = withContext(Dispatchers.Default) {
                engine.compile(requestJson, paths.map { overlay.getValue(it) })
            }
            val response = decodeResponse(native.json)
            lastResponse = response

            if (response.ok) {
                return CompileResult.Success(
                    outputs = buildOutputs(request, response, native.blobs),
                    warnings = response.diagnostics.warnings(),
                )
            }

            // Only retry for misses we have not already tried to satisfy, otherwise a resolver
            // that keeps returning null would spin forever.
            val pending = response.missing.filter { attempted.add(it.key()) }
            if (pending.isEmpty()) return response.toFailure()

            val resolvedAny = resolve(request, pending, overlay)
            if (!resolvedAny) return response.toFailure()
        }

        return lastResponse?.toFailure()
            ?: CompileResult.Failure(emptyList(), emptyList(), emptyList())
    }

    /** Registers every face in a font file. Returns how many faces were added. */
    public suspend fun addFont(bytes: ByteArray): Int = exclusive {
        withContext(Dispatchers.Default) { engine.addFont(bytes) }
    }

    /**
     * Removes one file from the instance's files, so the next compilation that needs it asks
     * [TypstConfig.fileResolver] again. Returns whether the file was there.
     *
     * [path] uses the same form as [CompileRequest.files], such as `/images/logo.png`.
     */
    public suspend fun removeFile(path: String): Boolean = exclusive {
        withContext(Dispatchers.Default) { engine.vfsRemove(path) }
    }

    /**
     * Removes every file fetched through [TypstConfig.fileResolver]. Packages and fonts are kept.
     * Returns how many files were removed.
     */
    public suspend fun clearFiles(): Int = exclusive {
        withContext(Dispatchers.Default) { engine.vfsClearFiles() }
    }

    /**
     * Removes every package fetched through [TypstConfig.packageResolver], so the next import of
     * one asks the resolver again. Returns how many packages were removed.
     */
    public suspend fun clearPackages(): Int = exclusive {
        withContext(Dispatchers.Default) { engine.vfsClearPackages() }
    }

    /**
     * Lists the files the instance keeps — those fetched through [TypstConfig.fileResolver] —
     * sorted by path, with their sizes. Packages are listed by [listPackages] instead.
     */
    public suspend fun listFiles(): List<VfsEntry> = tracked {
        inspect().files.map { VfsEntry(it.path, it.size) }
    }

    /** Lists the packages the instance keeps, sorted. */
    public suspend fun listPackages(): List<PackageSpec> = tracked {
        inspect().packages.map { parsePackageSpec(it) }
    }

    /**
     * Lists every font family the compiler can use, sorted: the embedded fonts, [TypstConfig.fonts]
     * and whatever [addFont] registered. A document asking for a family missing from here gets
     * Typst's `unknown font family` warning in [CompileResult.warnings].
     */
    public suspend fun fontFamilies(): List<String> = tracked {
        inspect().fonts.map { it.name }
    }

    private suspend fun inspect() =
        decodeInspection(withContext(Dispatchers.Default) { engine.inspect() })

    /**
     * Releases the engine. Safe to call at any time and more than once.
     *
     * Operations already running finish first, and the engine is released as the last of them
     * returns; one still waiting to change the instance fails with [IllegalStateException], as
     * does every later call.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (active.load() == 0) release()
    }

    /**
     * Runs [block] counted as an operation in progress, so [close] cannot free the engine
     * underneath it.
     *
     * [close] cannot suspend, so it cannot wait. Instead every operation registers itself before
     * checking the flag, and the last one out after a [close] releases the engine. Whichever order
     * the two race in, one of them sees the other: [close] sets the flag before reading the count,
     * and this raises the count before reading the flag.
     */
    private suspend fun <T> tracked(block: suspend () -> T): T {
        active.incrementAndFetch()
        try {
            check(!closed) { CLOSED }
            return block()
        } finally {
            if (active.decrementAndFetch() == 0 && closed) release()
        }
    }

    /** Runs [block] as an operation in progress that also has the instance to itself. */
    private suspend fun <T> exclusive(block: suspend () -> T): T = tracked {
        mutex.withLock {
            check(!closed) { CLOSED }
            block()
        }
    }

    private fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) engine.close()
    }

    /**
     * Fetches [pending] through the resolvers: the request's own file resolver first, whose
     * answers go into [overlay], then the instance's, whose answers are kept in the engine.
     * Returns true if anything was added.
     */
    private suspend fun resolve(
        request: CompileRequest,
        pending: List<WireMissing>,
        overlay: MutableMap<String, ByteArray>,
    ): Boolean {
        var resolved = false
        for (miss in pending) {
            when (miss) {
                is WireMissing.File -> {
                    val own = request.fileResolver?.resolve(miss.path)
                    if (own != null) {
                        overlay[miss.path] = own
                        resolved = true
                        continue
                    }
                    val shared = config.fileResolver?.resolve(miss.path) ?: continue
                    store { engine.vfsPut(miss.path, shared) }
                    resolved = true
                }

                is WireMissing.Package -> {
                    val spec = PackageSpec(miss.namespace, miss.name, miss.version)
                    val archive = config.packageResolver?.resolve(spec) ?: continue
                    store { engine.vfsPutPackage(spec.toString(), archive) }
                    resolved = true
                }
            }
        }
        return resolved
    }

    /**
     * Writes into the instance's files, alone, from within a compilation already [tracked].
     *
     * Unlike [exclusive] this does not refuse a closed instance: the compilation was running when
     * [close] came, and an operation in progress is allowed to finish.
     */
    private suspend fun store(block: suspend () -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.Default) { block() }
        }
    }

    private fun buildOutputs(
        request: CompileRequest,
        response: WireCompileResponse,
        blobs: List<ByteArray>,
    ): List<Output> = response.outputs.mapIndexed { index, meta ->
        val slice = blobs.subList(meta.blobStart, meta.blobStart + meta.blobCount)
        when (request.outputs.getOrNull(index)) {
            is OutputFormat.Pdf -> Output.Pdf(slice.single())
            is OutputFormat.Svg -> Output.Svg(slice.map { it.decodeToString() })
            is OutputFormat.Png -> Output.Png(slice)
            is OutputFormat.Query -> Output.Query(slice.single().decodeToString())
            null -> throw TypstException(
                "Native layer returned ${response.outputs.size} outputs but " +
                    "${request.outputs.size} were requested.",
            )
        }
    }

    public companion object {
        /** Creates and initialises an engine. */
        public suspend fun create(config: TypstConfig = TypstConfig()): Typst =
            withContext(Dispatchers.Default) {
                val engine = createNativeEngine(
                    EngineOptions(
                        configJson = encodeConfig(config),
                        webAssetBaseUrl = config.webAssetBaseUrl,
                    ),
                )
                try {
                    config.fonts.forEach { engine.addFont(it) }
                } catch (error: Throwable) {
                    engine.close()
                    throw error
                }
                Typst(engine, config)
            }
    }
}

private const val CLOSED = "This Typst instance has been closed."

/** Parses `@namespace/name:version`, the form the engine reports packages in. */
private fun parsePackageSpec(text: String): PackageSpec {
    val slash = text.indexOf('/')
    val colon = text.lastIndexOf(':')
    if (!text.startsWith('@') || slash < 0 || colon < slash) {
        throw TypstException("Native layer reported a malformed package spec: $text")
    }
    return PackageSpec(
        namespace = text.substring(1, slash),
        name = text.substring(slash + 1, colon),
        version = text.substring(colon + 1),
    )
}

private fun WireMissing.key(): String = when (this) {
    is WireMissing.File -> "file:$path"
    is WireMissing.Package -> "package:@$namespace/$name:$version"
}

private fun WireCompileResponse.toFailure(): CompileResult.Failure = CompileResult.Failure(
    errors = diagnostics.filter { it.severity == "error" }.map { it.toDiagnostic() },
    warnings = diagnostics.warnings(),
    unresolved = missing.map { miss ->
        when (miss) {
            is WireMissing.File -> Unresolved.File(miss.path)
            is WireMissing.Package ->
                Unresolved.Package(PackageSpec(miss.namespace, miss.name, miss.version))
        }
    },
)

private fun List<io.github.phfneves.typst.internal.WireDiagnostic>.warnings(): List<Diagnostic> =
    filter { it.severity == "warning" }.map { it.toDiagnostic() }
