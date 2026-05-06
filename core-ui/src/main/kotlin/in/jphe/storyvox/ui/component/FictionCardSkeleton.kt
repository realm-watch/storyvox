package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Skeleton placeholder shaped like a [FictionCoverThumb] + title + author stack.
 * Sized by the caller's modifier so it can flow into either a fixed-size strip
 * or an adaptive grid cell without clobbering width.
 */
@Composable
fun FictionCardSkeleton(
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Cover slot gets the magical brass sigil — reads as "we're conjuring
        // a fiction" rather than the generic alpha-pulse rectangle that users
        // mistake for empty content.
        MagicSkeletonTile(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = MaterialTheme.shapes.medium,
        )
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        )
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp),
        )
    }
}
