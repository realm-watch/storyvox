package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Skeleton placeholder shaped like the FictionDetailScreen — hero (cover + title bars),
 * synopsis paragraph, and a stack of chapter rows. Shown while the rate-limited
 * detail fetch is in flight (1-2s on first tap).
 */
@Composable
fun FictionDetailSkeleton(
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            SkeletonBlock(
                modifier = Modifier.size(width = 120.dp, height = 180.dp),
                shape = MaterialTheme.shapes.medium,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.9f).height(20.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.7f).height(20.dp))
                Spacer(Modifier.height(spacing.xxs))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.5f).height(14.dp))
                Spacer(Modifier.height(spacing.xs))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth().height(12.dp))
            SkeletonBlock(modifier = Modifier.fillMaxWidth().height(12.dp))
            SkeletonBlock(modifier = Modifier.fillMaxWidth().height(12.dp))
            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f).height(12.dp))
        }

        Spacer(Modifier.height(spacing.lg))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            repeat(6) {
                SkeletonBlock(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
    }
}
