package io.github.phfneves.typst

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PackageResolversTest {

    private val cetz = PackageSpec("preview", "cetz", "0.4.2")
    private val local = PackageSpec("local", "house-style", "1.0.0")

    @Test
    fun orElseAsksTheFallbackOnlyForWhatThePrimaryLacks() = runTest {
        val asked = mutableListOf<PackageSpec>()
        val primary = PackageResolver { spec -> if (spec == local) byteArrayOf(1) else null }
        val fallback = PackageResolver { spec ->
            asked += spec
            byteArrayOf(2)
        }
        val chained = primary.orElse(fallback)

        assertContentEquals(byteArrayOf(1), chained.resolve(local))
        assertContentEquals(byteArrayOf(2), chained.resolve(cetz))
        assertEquals(listOf(cetz), asked)
    }

    @Test
    fun recordsWhatWasAskedForAndWhatWasNotServed() = runTest {
        val recorder = RecordingPackageResolver { spec -> if (spec == local) byteArrayOf(1) else null }

        recorder.resolve(cetz)
        recorder.resolve(local)
        recorder.resolve(cetz)

        assertEquals(listOf(cetz, local), recorder.requested)
        assertEquals(listOf(cetz), recorder.unresolved)
    }

    @Test
    fun recordsTheImportsOfACompilation() = runTest {
        val recorder = RecordingPackageResolver { null }

        Typst.create(TypstConfig(packageResolver = recorder)).use { typst ->
            val result = typst.compile(
                CompileRequest.of(
                    "#import \"@preview/cetz:0.4.2\"\n#import \"@local/house-style:1.0.0\"",
                ),
            )
            assertIs<CompileResult.Failure>(result)
        }

        // Evaluation stops at the first package it cannot get, so the second is never reached.
        assertEquals(listOf(cetz), recorder.requested)
        assertEquals(listOf(cetz), recorder.unresolved)
    }

    @Test
    fun recordsNothingBeforeAnyRequest() {
        val recorder = RecordingPackageResolver { null }
        assertEquals(emptyList(), recorder.requested)
        assertNull(recorder.unresolved.firstOrNull())
    }
}
