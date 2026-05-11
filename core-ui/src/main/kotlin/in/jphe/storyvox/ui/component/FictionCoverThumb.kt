package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlin.math.min

/**
 * Async cover image with a brass-tinted sigil placeholder.
 *
 * When [coverUrl] is null or fails to load, draws a static Library
 * Nocturne sigil — faint brass ring + six-pointed star encircling the
 * fiction's [monogram]. The same visual family as [MagicSkeletonTile]
 * but static: a sigil reads as "this fiction is bound to this mark",
 * not "we're conjuring something". Callers compute [monogram] via
 * [fictionMonogram] which prefers author → title → brass star, so RSS
 * feeds and other coverless-and-authorless fictions still render an
 * intentional Library Nocturne mark rather than a `?` (#322).
 */
@Composable
fun FictionCoverThumb(
    coverUrl: String?,
    title: String,
    monogram: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
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
            loading = { MonogramSigilTile(monogram = monogram) },
            error = { MonogramSigilTile(monogram = monogram) },
        )
    }
}

/**
 * Static brass-sigil placeholder shown in place of a cover image when one
 * isn't available. Visual family deliberately matches [MagicSkeletonTile]
 * (the loading-state version) — same substrate, ring, six-pointed star,
 * brass palette — but holds still: a sigil reads as "this fiction is
 * bound to this mark," not "we're conjuring something."
 *
 * Monogram font-size scales with the container's smaller dimension via
 * [BoxWithConstraints] so the same component looks right in both the
 * 56-dp Follows thumbnail and the 220-dp audiobook player cover.
 */
@Composable
private fun MonogramSigilTile(monogram: String) {
    val brass = MaterialTheme.colorScheme.primary
    val brassDim = brass.copy(alpha = 0.40f)
    val brassFaint = brass.copy(alpha = 0.18f)
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(substrate),
        contentAlignment = Alignment.Center,
    ) {
        // Aged-leather radial wash: lighter at the edges, slightly
        // darker at the center so the sigil reads against a faint
        // halo instead of a flat substrate.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            substrate.copy(alpha = 0f),
                            substrate.copy(alpha = 0.45f),
                        ),
                    ),
                ),
        )
        // Brass sigil — outer ring + six-pointed star, sized to ~70%
        // of the tile so the monogram can sit comfortably inside.
        Canvas(modifier = Modifier.fillMaxSize(0.72f)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val ringStroke = (radius * 0.05f).coerceAtLeast(1.5f)
            val starStroke = (radius * 0.07f).coerceAtLeast(2.0f)

            drawCircle(
                color = brassFaint,
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = ringStroke),
            )
            // Six-pointed star = two overlaid equilateral triangles
            drawTriangle(center, radius * 0.86f, 0f, brassDim, starStroke)
            drawTriangle(
                center,
                radius * 0.86f,
                60f,
                brassDim.copy(alpha = brassDim.alpha * 0.85f),
                starStroke,
            )
        }
        // Monogram letter inside the sigil — serif, brass, size scales
        // with the container so it looks right at 56 dp and 220 dp.
        val smallerDim = min(maxWidth.value, maxHeight.value)
        val monogramSize = (smallerDim * 0.32f).coerceIn(14f, 56f).sp
        Text(
            text = monogram,
            style = MaterialTheme.typography.displayMedium.copy(fontSize = monogramSize),
            color = brass,
        )
    }
}

// drawTriangle is shared with MagicSkeletonTile via the internal helper
// in SkeletonShimmer.kt — same star geometry, same StrokeCap.Round, no
// duplicated drift between the loading and static placeholders.
