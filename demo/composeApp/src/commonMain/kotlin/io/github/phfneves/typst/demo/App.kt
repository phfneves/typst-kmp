package io.github.phfneves.typst.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The demo, in one screen: a Typst source on one side, the pages it produces on the other.
 *
 * Nothing here is platform-specific — the same composable is what `androidApp`, `desktopApp` and
 * `iosApp` each start.
 */
@Composable
fun App() {
    MaterialTheme {
        val state = rememberTypstDemoState()
        val exporter = rememberPdfExporter()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        Scaffold(
            topBar = { DemoTopBar(state) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                DemoBottomBar(
                    state = state,
                    onExport = {
                        val bytes = state.pdf ?: return@DemoBottomBar
                        scope.launch {
                            val message = try {
                                "PDF em " + exporter.share("typst-kmp-demo.pdf", bytes)
                            } catch (error: Throwable) {
                                "Falha ao exportar: " + (error.message ?: error.toString())
                            }
                            snackbar.showSnackbar(message)
                        }
                    },
                )
            },
        ) { padding ->
            val fatal = state.fatal
            if (fatal != null) {
                FatalMessage(fatal, Modifier.padding(padding))
                return@Scaffold
            }

            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                // One breakpoint, because there are only two layouts worth having: side by side in
                // a desktop window, and one pane at a time on a phone.
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        SourceEditor(state, Modifier.weight(1f).fillMaxSize())
                        VerticalDivider()
                        PreviewPane(state, Modifier.weight(1f).fillMaxSize())
                    }
                } else {
                    NarrowLayout(state)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoTopBar(state: TypstDemoState) {
    TopAppBar(
        title = {
            Column {
                Text("typst-kmp", style = MaterialTheme.typography.titleMedium)
                Text(platformLabel, style = MaterialTheme.typography.labelSmall)
            }
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 12.dp),
            ) {
                TypstSamples.list.forEach { sample ->
                    FilterChip(
                        selected = state.sample === sample,
                        onClick = { state.selectSample(sample) },
                        label = { Text(sample.name) },
                    )
                }
            }
        },
    )
}

@Composable
private fun DemoBottomBar(state: TypstDemoState, onExport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = state.statusLine(), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onExport, enabled = state.pdf != null) {
            Text("Exportar PDF")
        }
    }
}

@Composable
private fun SourceEditor(state: TypstDemoState, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = state.source,
        onValueChange = state::editSource,
        modifier = modifier.padding(12.dp),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        label = { Text(MAIN_PATH) },
        supportingText = {
            if (state.sample.extraFiles.isNotEmpty()) {
                Text("importa " + state.sample.extraFiles.keys.joinToString())
            }
        },
    )
}

@Composable
private fun PreviewPane(state: TypstDemoState, modifier: Modifier = Modifier) {
    Column(modifier) {
        PagePreview(
            pages = state.pages,
            phase = state.phase,
            modifier = Modifier.weight(1f),
        )
        if (state.errors.isNotEmpty() || state.warnings.isNotEmpty()) {
            DiagnosticsPanel(
                errors = state.errors,
                warnings = state.warnings,
                unresolved = state.unresolved,
                modifier = Modifier.heightIn(max = 220.dp),
            )
        }
    }
}

@Composable
private fun NarrowLayout(state: TypstDemoState) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Fonte") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Página") })
        }
        if (tab == 0) {
            SourceEditor(state, Modifier.weight(1f).fillMaxWidth())
        } else {
            PreviewPane(state, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun FatalMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("O compilador não iniciou", style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun TypstDemoState.statusLine(): String = when (phase) {
    Phase.Starting -> "Iniciando o compilador…"
    Phase.Compiling -> "Compilando…"
    Phase.Ready -> buildString {
        append(pages.size).append(if (pages.size == 1) " página" else " páginas")
        pdf?.let { bytes -> append(" · PDF de ").append(bytes.size / 1024).append(" KB") }
        if (warnings.isNotEmpty()) append(" · ").append(warnings.size).append(" aviso(s)")
    }
    Phase.Failed -> "${errors.size} erro(s) — a última versão válida continua na tela"
}
