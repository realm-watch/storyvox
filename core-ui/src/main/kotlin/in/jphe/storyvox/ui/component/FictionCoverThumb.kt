package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.SubcomposeAsyncImage

/**
 * Async cover image with a brass-tinted placeholder.
 * Placeholder shows the author's first initial in brass on warm dark.
 */
@Composable
fun FictionCoverThumb(
    coverUrl: String?,
    title: String,
    authorInitial: Char,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholderBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = "Cover for $title" },
    ) {
        SubcomposeAsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(modifier = Modifier.fillMaxSize().background(placeholderBrush), contentAlignment = Alignment.Center) {
                    Text(
                        text = authorInitial.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            error = {
                Box(modifier = Modifier.fillMaxSize().background(placeholderBrush), contentAlignment = Alignment.Center) {
                    Text(
                        text = authorInitial.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
}
