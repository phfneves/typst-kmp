package io.github.phfneves.typst.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.phfneves.typst.Diagnostic
import io.github.phfneves.typst.Severity
import io.github.phfneves.typst.Unresolved

/**
 * Everything the compiler had to say about the last attempt.
 *
 * Each entry keeps its own path and line, which is what makes an error inside an imported file
 * readable: the location is `/helpers.typ:2`, not "somewhere in the document you are editing".
 */
@Composable
fun DiagnosticsPanel(
    errors: List<Diagnostic>,
    warnings: List<Diagnostic>,
    unresolved: List<Unresolved>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (errors.isEmpty()) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(errors.size) { index -> DiagnosticRow(errors[index]) }
            items(warnings.size) { index -> DiagnosticRow(warnings[index]) }

            if (unresolved.isNotEmpty()) {
                item {
                    Text(
                        text = "Não resolvido: " + unresolved.joinToString { entry ->
                            when (entry) {
                                is Unresolved.File -> entry.path
                                is Unresolved.Package -> entry.spec.toString()
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(diagnostic: Diagnostic) {
    val accent = when (diagnostic.severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = diagnostic.severity.name.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = diagnostic.message,
            style = MaterialTheme.typography.bodyMedium,
        )
        diagnostic.location()?.let { location ->
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        diagnostic.hints.forEach { hint ->
            Text(
                text = "dica: $hint",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        diagnostic.trace.forEach { point ->
            Text(
                text = "em ${point.message}" +
                    (point.path?.let { path -> " ($path${point.line?.let { ":$it" } ?: ""})" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `/helpers.typ:2:19`, with whatever parts the diagnostic actually carries. */
private fun Diagnostic.location(): String? {
    val path = path ?: return null
    return buildString {
        append(path)
        line?.let { append(':').append(it) }
        column?.let { append(':').append(it) }
    }
}

