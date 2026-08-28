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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Typst compiler instance.
 *
 * Create one with [create], reuse it, and [close] it when done. Instances are safe to share
 * between coroutines; the underlying engine serialises access internally.
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

    private var closed = false

    /**
     * Compiles [request], resolving missing files and packages as it goes.
     *
     * The native compiler cannot perform I/O, so a missing dependency comes back as a structured
     * miss rather than an error. This method fetches those through
     * [TypstConfig.fileResolver] / [TypstConfig.packageResolver], feeds them into the VFS and
     * retries, up to [TypstConfig.maxResolveRounds] times.
     */
    public suspend fun compile(request: CompileRequest): CompileResult {
        check(!closed) { "This Typst instance has been closed." }

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
    public suspend fun addFont(bytes: ByteArray): Int {
        check(!closed) { "This Typst instance has been closed." }
        return withContext(Dispatchers.Default) { engine.addFont(bytes) }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.close()
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
