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
 * Async cover image with a multi-tier fallback cascade.
 *
 * The cascade (v0.5.51, #notion-beautiful-covers):
 *
 *  1. **Remote cover URL** — `SubcomposeAsyncImage(coverUrl)` if
 *     [coverUrl] is non-null. Loaded with [contentScale] (default
 *     `Crop`) which biases focal-center, so Notion banner-aspect
 *     covers (~5:1) crop to the middle band of the image — the most
 *     consistent place to find the page's hero illustration. Coil's
 *     disk cache + retry handles transient network failures; S3-signed
 *     URL expiry falls through to the branded fallback below.
 *
 *  2. **Branded synthetic cover** ([BrandedCoverTile]) — if [coverUrl]
 *     is null OR the load errors out AND [title] is non-blank. Renders
 *     a hand-designed-looking jacket with [author] line and a
 *     family-specific watermark ([sourceFamily]). Pure-composable, no
 *     network, so it shows instantly and survives any image-load
 *     failure mode.
 *
 *  3. **Brass-sigil monogram tile** — if [title] is blank (RSS feeds
 *     where only the index was parsed, first-cold-launch entries with
 *     no title yet). [monogram] comes from `fictionMonogram(author,
 *     title)` which falls back to the brass star glyph (#322).
 *
 * The loading slot still uses the monogram tile rather than the
 * branded cover so it's clearly differentiable from the terminal
 * fallback — a sigil reads as "this fiction is bound to this mark",
 * not "we gave up trying to load."
 *
 * @param coverUrl Remote image URL; null skips straight to the
 *   branded fallback.
 * @param title Used for the branded tile's title and as the cover's
 *   content description.
 * @param monogram One- or two-letter mark used by the third-tier
 *   sigil tile when title is blank.
 * @param author Optional — rendered as a brass label on the branded
 *   tile. Skipped when null/blank.
 * @param sourceFamily Which watermark glyph the branded tile uses.
 *   Defaults to [CoverSourceFamily.Generic] for backwards-compatible
 *   call sites that don't yet know their source family.
 */
@Composable
fun FictionCoverThumb(
    coverUrl: String?,
    title: String,
    monogram: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    author: String? = null,
    sourceFamily: CoverSourceFamily = CoverSourceFamily.Generic,
) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = "Cover for $title" },
    ) {
        val brandedOrMonogram: @Composable () -> Unit = {
            if (title.isNotBlank()) {
                BrandedCoverTile(
                    title = title,
                    author = author,
                    sourceFamily = sourceFamily,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MonogramSigilTile(monogram = monogram)
            }
        }

        if (coverUrl.isNullOrBlank()) {
            // Skip the AsyncImage round-trip entirely when we already
            // know there's no URL — render the branded fallback
            // straight away rather than briefly showing the loading
            // sigil and then crossfading.
            brandedOrMonogram()
        } else {
            SubcomposeAsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { MonogramSigilTile(monogram = monogram) },
                error = { brandedOrMonogram() },
            )
        }
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
