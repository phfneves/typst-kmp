package io.github.phfneves.typst.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The rendered pages, one image each.
 *
 * `OutputFormat.Png` returns one entry per page, so there is nothing to slice or paginate here —
 * the list of byte arrays is already the list of pages.
 */
@Composable
fun PagePreview(
    pages: List<ImageBitmap>,
    phase: Phase,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            pages.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(pages) { page ->
                    Image(
                        bitmap = page,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Typst pages are white; without this they float on the app's own
                            // background and lose their edges in dark mode.
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }

            phase == Phase.Starting || phase == Phase.Compiling -> CircularProgressIndicator()

            else -> Text(
                text = "Nada para mostrar ainda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
