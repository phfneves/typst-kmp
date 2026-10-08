package io.github.phfneves.typst

import io.github.phfneves.typst.internal.EngineOptions
import io.github.phfneves.typst.internal.NativeEngine
import io.github.phfneves.typst.internal.WireCompileResponse
import io.github.phfneves.typst.internal.WireMissing
import io.github.phfneves.typst.internal.createNativeEngine
import io.github.phfneves.typst.internal.decodeResponse
import io.github.phfneves.typst.internal.encodeConfig
import io.github.phfneves.typst.internal.encodeRequest
import io.github.phfneves.typst.internal.toDiagnostic
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
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
 * An instance is safe to share between coroutines and threads. Every operation, [compile] above
 * all, runs alone: a compilation holds the instance from seeding [CompileRequest.files] to its last
 * output, so concurrent compilations never see each other's files even when they use the same
 * paths. They queue rather than run in parallel; create more instances if you need throughput.
 *
 * Because the resolvers run inside that exclusive section, a [FileResolver] or [PackageResolver]
 * must not call back into the same instance, or it waits for itself forever.
 *
 * ## The virtual file system
 *
 * Every file the compiler sees lives in an in-memory file system owned by this instance, and it
 * persists from one compilation to the next:
 *
 * * [CompileRequest.files] are written before each compilation, replacing whatever was at those
 *   paths. Pass data that changes, such as a JSON payload, this way.
 * * A file fetched through [TypstConfig.fileResolver] is kept, and the resolver is not asked for
 *   that path again. That suits assets that never change under a given path; for anything that
 *   does, pass it in the request instead, or [removeFile] it when it changes.
 * * Packages fetched through [TypstConfig.packageResolver] are kept too. A published version
 *   never changes, so that is a cache, not a hazard.
 *
 * Nothing is removed on its own. When the documents an instance compiles keep bringing new paths,
 * such as an image per record, call [clearFiles] now and then — or after every compilation, which
 * costs no more than resolving the files again. Fonts and packages survive it.
 *
 * ```kotlin
 * Typst.create().use { typst ->
 *     val pdf = typst.compile(CompileRequest.of("= Olá\nMundo")).getOrThrow()
 * }
 * ```
 */
public class Typst private constructor(
    private val engine: NativeEngine,
    private val config: TypstConfig,
) : AutoCloseable {

    /** Serialises every engine operation; see "Sharing" above. */
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    /**
     * Compiles [request], resolving missing files and packages as it goes.
     *
     * The native compiler cannot perform I/O, so a missing dependency comes back as a structured
     * miss rather than an error. This method fetches those through
     * [TypstConfig.fileResolver] / [TypstConfig.packageResolver], feeds them into the VFS and
     * retries, up to [TypstConfig.maxResolveRounds] times.
     */
    public suspend fun compile(request: CompileRequest): CompileResult = exclusive {
        compileLocked(request)
    }

    private suspend fun compileLocked(request: CompileRequest): CompileResult {
        val requestJson = encodeRequest(request)
        val attempted = mutableSetOf<String>()

        withContext(Dispatchers.Default) {
            for ((path, bytes) in request.files) {
                engine.vfsPut(path, bytes)
            }
        }

        var lastResponse: WireCompileResponse? = null
        repeat(config.maxResolveRounds.coerceAtLeast(1)) {
            val native = withContext(Dispatchers.Default) { engine.compile(requestJson) }
            val response = decodeResponse(native.json)
            lastResponse = response

            if (response.ok) {
                return CompileResult.Success(
                    outputs = buildOutputs(request, response, native.blobs),
                    warnings = response.diagnostics.warnings(),
                )
            }

            // Only retry for misses we have not already tried to satisfy, otherwise a resolver
            // that keeps returning null would spin until the round budget runs out.
            val pending = response.missing.filter { attempted.add(it.key()) }
            if (pending.isEmpty()) return response.toFailure()

            val resolvedAny = resolve(pending)
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
     * Removes one file from the virtual file system, so the next compilation that needs it asks
     * [TypstConfig.fileResolver] again. Returns whether the file was there.
     *
     * [path] uses the same form as [CompileRequest.files], such as `/images/logo.png`.
     */
    public suspend fun removeFile(path: String): Boolean = exclusive {
        withContext(Dispatchers.Default) { engine.vfsRemove(path) }
    }

    /**
     * Removes every file from the virtual file system: those passed in [CompileRequest.files] and
     * those fetched through [TypstConfig.fileResolver]. Packages and fonts are kept. Returns how
     * many files were removed.
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
     * Releases the engine. Safe to call at any time and more than once.
     *
     * An operation already running finishes first, and the engine is released as it returns; one
     * still waiting for its turn fails with [IllegalStateException], as does every later call.
     */
    override fun close() {
        if (closed) return
        closed = true
        releaseIfIdle()
    }

    /**
     * Runs [block] with the engine to itself.
     *
     * [close] cannot suspend, so it cannot wait for the operation in progress, and freeing the
     * engine underneath one would pull the native memory out from under it. Instead every exit
     * from here checks whether the instance was closed meanwhile and, if so, releases the engine
     * itself. Whichever of the two runs last does it: [close] sets the flag before trying the lock,
     * and this releases the lock before reading the flag.
     */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        check(!closed) { CLOSED }
        try {
            return mutex.withLock {
                check(!closed) { CLOSED }
                block()
            }
        } finally {
            if (closed) releaseIfIdle()
        }
    }

    private fun releaseIfIdle() {
        if (!mutex.tryLock()) return
        try {
            engine.close()
        } finally {
            mutex.unlock()
        }
    }

    /** Fetches [pending] through the configured resolvers. Returns true if anything was added. */
    private suspend fun resolve(pending: List<WireMissing>): Boolean {
        var resolved = false
        for (miss in pending) {
            when (miss) {
                is WireMissing.File -> {
                    val bytes = config.fileResolver?.resolve(miss.path) ?: continue
                    withContext(Dispatchers.Default) { engine.vfsPut(miss.path, bytes) }
                    resolved = true
                }

                is WireMissing.Package -> {
                    val spec = PackageSpec(miss.namespace, miss.name, miss.version)
                    val archive = config.packageResolver?.resolve(spec) ?: continue
                    withContext(Dispatchers.Default) {
                        engine.vfsPutPackage(spec.toString(), archive)
                    }
                    resolved = true
                }
            }
        }
        return resolved
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
