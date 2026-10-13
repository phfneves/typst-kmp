package io.github.phfneves.typst.universe

import io.github.phfneves.typst.PackageResolver
import io.github.phfneves.typst.PackageSpec
import io.github.phfneves.typst.TypstException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads `@preview` packages from Typst Universe, the way the `typst` CLI does.
 *
 * Every archive goes through [cache] first, and is stored there once downloaded. The instance
 * already keeps the packages it was given, so the cache is about the next instance, or the next
 * launch: [DirectoryPackageCache] keeps them on disk, and a directory it filled can later be
 * served offline by `DirectoryPackageResolver`.
 *
 * Returns `null` for any namespace other than `preview`, and for a package Universe does not
 * have; chain a resolver for `@local` packages with `orElse`. Any other failure to download
 * throws [UniverseException], so a network problem is not mistaken for a missing package.
 *
 * Bring your own [httpClient], with whichever Ktor engine the platform uses. It is not closed.
 */
public class UniversePackageResolver(
    private val httpClient: HttpClient,
    private val cache: PackageCache = InMemoryPackageCache(),
    baseUrl: String = DEFAULT_BASE_URL,
) : PackageResolver {

    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun resolve(spec: PackageSpec): ByteArray? {
        if (spec.namespace != PREVIEW) return null
        cache.get(spec)?.let { return it }

        val url = "$baseUrl/$PREVIEW/${spec.name}-${spec.version}.tar.gz"
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw UniverseException("Could not download $spec from $url: ${error.message}", error)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            throw UniverseException("Could not download $spec from $url: HTTP ${response.status}")
        }

        val archive = response.readRawBytes()
        cache.put(spec, archive)
        return archive
    }

    public companion object {
        /** Where the `typst` CLI downloads packages from. */
        public const val DEFAULT_BASE_URL: String = "https://packages.typst.org"

        private const val PREVIEW = "preview"
    }
}

/** A package could not be downloaded for a reason other than not existing. */
public class UniverseException(message: String, cause: Throwable? = null) :
    TypstException(message, cause)
