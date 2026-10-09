package io.github.phfneves.typst

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Asks this resolver first and [fallback] for whatever this one does not supply. */
public fun PackageResolver.orElse(fallback: PackageResolver): PackageResolver {
    val primary = this
    return PackageResolver { spec -> primary.resolve(spec) ?: fallback.resolve(spec) }
}

/**
 * Passes every request on to [delegate] and remembers which packages were asked for.
 *
 * Compiling a template once through it, on a fresh instance, lists every package the template
 * pulls in, its packages' own imports included: the set to vendor so the template compiles
 * offline. An instance asks for each package once and keeps it, so a second compilation on the
 * same instance records nothing new.
 *
 * ```kotlin
 * val recorder = RecordingPackageResolver(UniversePackageResolver(httpClient))
 * Typst.create(TypstConfig(packageResolver = recorder)).use { it.compilePdf(request) }
 * println(recorder.requested) // [@preview/cetz:0.4.2, @preview/oxifmt:1.0.0]
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
public class RecordingPackageResolver(private val delegate: PackageResolver) : PackageResolver {

    private val log = AtomicReference(emptyList<Request>())

    /** Every package asked for, in the order of the first request, without repeats. */
    public val requested: List<PackageSpec>
        get() = log.load().map { it.spec }.distinct()

    /** The packages [delegate] returned `null` for, in the same order. */
    public val unresolved: List<PackageSpec>
        get() {
            val requests = log.load()
            val served = requests.filter { it.served }.mapTo(mutableSetOf()) { it.spec }
            return requests.map { it.spec }.distinct().filterNot { it in served }
        }

    override suspend fun resolve(spec: PackageSpec): ByteArray? {
        val archive = delegate.resolve(spec)
        val request = Request(spec, served = archive != null)
        while (true) {
            val current = log.load()
            if (log.compareAndSet(current, current + request)) break
        }
        return archive
    }

    private class Request(val spec: PackageSpec, val served: Boolean)
}
