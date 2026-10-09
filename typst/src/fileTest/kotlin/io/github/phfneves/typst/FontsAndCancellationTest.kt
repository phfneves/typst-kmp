package io.github.phfneves.typst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What only platforms with a native engine can do; the browser's cannot be interrupted. */
class FontsAndCancellationTest {

    @Test
    fun cancellingInterruptsACompilationInProgress() = runTest(timeout = 2.minutes) {
        // A hundred million file reads: minutes of work, and a world access on every iteration.
        val endless = CompileRequest(
            main = "/main.typ",
            files = mapOf(
                "/main.typ" to """
                    #for i in range(10000) { for j in range(10000) { let _ = read("/a.txt") } }
                """.trimIndent().encodeToByteArray(),
                "/a.txt" to "a".encodeToByteArray(),
            ),
        )

        Typst.create().use { typst ->
            withContext(Dispatchers.Default) {
                val job = launch { typst.compile(endless) }
                delay(500) // Well inside the native call by now.

                val cancelled = TimeSource.Monotonic.markNow()
                job.cancelAndJoin()

                assertTrue(job.isCancelled)
                assertTrue(cancelled.elapsedNow() < 10.seconds, "took ${cancelled.elapsedNow()}")
            }

            // The instance is still good for the next compilation.
            assertIs<CompileResult.Success>(typst.compile(CompileRequest.of("Still here")))
        }
    }

    @Test
    fun aFontPathThatDoesNotExistFailsCreation() = runTest {
        val error = assertFailsWith<TypstNativeException> {
            Typst.create(TypstConfig(fontPaths = listOf("/no/such/fonts"))).close()
        }
        assertTrue(error.message.orEmpty().contains("/no/such/fonts"), error.message)
    }

    @Test
    fun systemFontsAddToTheEmbeddedOnes() = runTest {
        val embedded = Typst.create().use { it.fontFamilies() }
        val withSystem = Typst.create(TypstConfig(includeSystemFonts = true)).use { typst ->
            assertIs<CompileResult.Success>(typst.compile(CompileRequest.of("Hello")))
            typst.fontFamilies()
        }

        assertTrue(withSystem.containsAll(embedded))
        // Every macOS ships fonts; elsewhere, a bare CI machine or simulator may have none.
        if (platformName.startsWith("macos")) {
            assertTrue(withSystem.size > embedded.size, "no system fonts found")
        }
    }
}
