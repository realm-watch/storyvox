package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Skeleton placeholder shaped like a [FictionCoverThumb] + title + author stack.
 * Used by BrowseScreen during the rate-limited fetch so the row doesn't feel blank.
 */
@Composable
fun FictionCardSkeleton(
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.size(width = 140.dp, height = 240.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SkeletonBlock(
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
