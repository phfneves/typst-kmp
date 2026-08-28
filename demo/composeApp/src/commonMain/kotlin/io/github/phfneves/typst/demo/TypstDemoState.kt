package io.github.phfneves.typst.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.github.phfneves.typst.CompileRequest
import io.github.phfneves.typst.CompileResult
import io.github.phfneves.typst.Diagnostic
import io.github.phfneves.typst.Output
import io.github.phfneves.typst.OutputFormat
import io.github.phfneves.typst.Typst
import io.github.phfneves.typst.TypstDate
import io.github.phfneves.typst.Unresolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Where a compilation currently stands, which is all the status line needs to know. */
enum class Phase { Starting, Compiling, Ready, Failed }

/**
 * The whole demo, minus the pixels.
 *
 * One [Typst] instance is created up front and reused for every keystroke — that is the intended
 * lifecycle, and creating one per compilation would re-register the embedded fonts each time.
 */
@Stable
class TypstDemoState(private val scope: CoroutineScope) {

    var sample: TypstSample by mutableStateOf(TypstSamples.first)
        private set

    var source: String by mutableStateOf(TypstSamples.first.main)
        private set

    var pages: List<ImageBitmap> by mutableStateOf(emptyList())
        private set

    var pdf: ByteArray? by mutableStateOf(null)
        private set

    var errors: List<Diagnostic> by mutableStateOf(emptyList())
        private set

    var warnings: List<Diagnostic> by mutableStateOf(emptyList())
        private set

    var unresolved: List<Unresolved> by mutableStateOf(emptyList())
        private set

    var phase: Phase by mutableStateOf(Phase.Starting)
        private set

    /** Set when the engine itself could not start — almost always a missing native library. */
    var fatal: String? by mutableStateOf(null)
        private set

    private var engine: Typst? = null
    private var pending: Job? = null

    fun start() {
        scope.launch {
            engine = try {
                Typst.create()
            } catch (error: Throwable) {
                fatal = error.message ?: error.toString()
                phase = Phase.Failed
                return@launch
            }
            compile()
        }
    }

    fun close() {
        pending?.cancel()
        engine?.close()
        engine = null
    }

    fun selectSample(selected: TypstSample) {
        sample = selected
        source = selected.main
        scheduleCompile(debounce = false)
    }

    fun editSource(text: String) {
        source = text
        scheduleCompile(debounce = true)
    }

    /**
     * Recompiles, dropping whatever run is already queued.
     *
     * Typing is debounced because a compilation is measured in tens of milliseconds, not
     * microseconds, and every intermediate keystroke would otherwise queue one up.
     */
    private fun scheduleCompile(debounce: Boolean) {
        pending?.cancel()
        pending = scope.launch {
            if (debounce) delay(DEBOUNCE_MILLIS)
            compile()
        }
    }

    private suspend fun compile() {
        val typst = engine ?: return
        phase = Phase.Compiling

        val files = buildMap {
            putAll(sample.extraFiles)
            put(MAIN_PATH, source)
        }

        // One call, two artifacts: the preview and the export come out of a single layout pass.
        val request = CompileRequest(
            main = MAIN_PATH,
            files = files.mapValues { (_, text) -> text.encodeToByteArray() },
            inputs = mapOf("platform" to platformLabel),
            outputs = listOf(
                OutputFormat.Png(pixelPerPt = PREVIEW_PIXEL_PER_PT),
                OutputFormat.Pdf(creator = "typst-kmp demo"),
            ),
            // Without a date the document sees `none` from datetime.today(); the samples print it.
            now = TypstDate(year = 2026, month = 8, day = 24),
        )

        when (val result = typst.compile(request)) {
            is CompileResult.Success -> {
                val png = result.outputs.filterIsInstance<Output.Png>().single()
                // Decoding a couple of full pages is heavy enough to skip a frame on the UI thread.
                val decoded = withContext(Dispatchers.Default) {
                    png.pages.map { page -> page.decodeToImageBitmap() }
                }
                pages = decoded
                pdf = result.outputs.filterIsInstance<Output.Pdf>().single().bytes
                errors = emptyList()
                unresolved = emptyList()
                warnings = result.warnings
                phase = Phase.Ready
            }

            is CompileResult.Failure -> {
                // The previous pages stay on screen: an unfinished edit should not blank the
                // preview, it should just explain itself in the panel below.
                errors = result.errors
                warnings = result.warnings
                unresolved = result.unresolved
                phase = Phase.Failed
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
        const val PREVIEW_PIXEL_PER_PT = 2f
    }
}

@Composable
fun rememberTypstDemoState(): TypstDemoState {
    val scope = rememberCoroutineScope()
    val state = remember { TypstDemoState(scope) }
    DisposableEffect(state) {
        state.start()
        onDispose { state.close() }
    }
    return state
}
