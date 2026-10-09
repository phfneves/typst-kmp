package io.github.phfneves.typst.universe

import io.github.phfneves.typst.PackageSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UniversePackageResolverTest {

    private val cetz = PackageSpec("preview", "cetz", "0.4.2")
    private val archive = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)

    private val requests = mutableListOf<String>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(
        MockEngine { request ->
            requests += request.url.toString()
            if (status == HttpStatusCode.OK) respond(archive) else respondError(status)
        },
    )

    @Test
    fun downloadsFromTheUniverseUrl() = runTest {
        val resolved = UniversePackageResolver(client()).resolve(cetz)

        assertContentEquals(archive, resolved)
        assertEquals(listOf("https://packages.typst.org/preview/cetz-0.4.2.tar.gz"), requests)
    }

    @Test
    fun downloadsEachPackageOnceThroughTheCache() = runTest {
        val resolver = UniversePackageResolver(client())

        resolver.resolve(cetz)
        resolver.resolve(cetz)

        assertEquals(1, requests.size)
    }

    @Test
    fun usesAnotherBaseUrl() = runTest {
        UniversePackageResolver(client(), baseUrl = "https://mirror.example/typst/").resolve(cetz)
        assertEquals(listOf("https://mirror.example/typst/preview/cetz-0.4.2.tar.gz"), requests)
    }

    @Test
    fun leavesOtherNamespacesAlone() = runTest {
        assertNull(UniversePackageResolver(client()).resolve(PackageSpec("local", "cetz", "0.4.2")))
        assertEquals(emptyList(), requests)
    }

    @Test
    fun treatsNotFoundAsMissing() = runTest {
        assertNull(UniversePackageResolver(client(HttpStatusCode.NotFound)).resolve(cetz))
    }

    @Test
    fun throwsOnAnyOtherFailure() = runTest {
        val resolver = UniversePackageResolver(client(HttpStatusCode.ServiceUnavailable))
        val error = assertFailsWith<UniverseException> { resolver.resolve(cetz) }
        assertEquals(true, error.message?.contains("503"))
    }

    @Test
    fun storesWhatItDownloadsInTheGivenCache() = runTest {
        val cache = InMemoryPackageCache()
        UniversePackageResolver(client(), cache).resolve(cetz)
        assertContentEquals(archive, cache.get(cetz))
    }
}
