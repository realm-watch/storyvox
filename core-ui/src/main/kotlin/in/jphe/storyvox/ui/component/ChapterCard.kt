package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Immutable
data class ChapterCardState(
    val number: Int,
    val title: String,
    val publishedRelative: String,
    val durationLabel: String,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
    val isCurrent: Boolean,
)

@Composable
fun ChapterCard(
    state: ChapterCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val highlight = if (state.isCurrent)
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Issue #266 — the un-merged `semantics {}` here used to split the
            // a11y tree: this node got role=Button + contentDescription, while
            // the Card's own clickable lived on a child node. Espresso/
            // UIAutomator reported clickable='false' on the row because the
            // role node and the action node were different. Merging
            // descendants flattens them, so the row reads as a single
            // clickable Button with our content-desc.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Chapter ${state.number}, ${state.title}, ${state.durationLabel}" +
                    if (state.isDownloaded) ", downloaded" else ""
            },
        colors = highlight,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = state.number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Issue #256 — the left-side brass index already shows the
                    // chapter number (e.g. "06"), so titles that start with
                    // their own "Ch. N -" / "Chapter N:" prefix produced two
                    // numbers racing on the same row: brass '06' + 'CH. 116
                    // - Insurmountable Foe'. Strip the redundant prefix so
                    // the visible title is the actual title; the brass
                    // numeral remains the canonical chapter number.
                    stripChapterPrefix(state.title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(state.publishedRelative, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.durationLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.isDownloaded) {
                Icon(
                    imageVector = Icons.Filled.OfflineBolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                imageVector = if (state.isFinished) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (state.isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Issue #256 — strip a redundant chapter-number prefix from a chapter
 * title. RR titles routinely look like:
 *   "Ch. 116 - Insurmountable Foe"
 *   "Chapter 7: The Brass Sigil"
 *   "Ch.7 - Greed"
 *   "CH. 0 - "
 * which collide with the brass left-side index. The regex covers the
 * common shapes; if no prefix matches the title is returned untouched
 * so non-conforming sources (mdbook chapters, RSS items) keep rendering
 * as-is. The trailing colon strip in #265 lives at a different layer
 * (source side) but the same intent — collapse double signals into one.
 */
internal fun stripChapterPrefix(title: String): String {
    // Matches: optional `Ch`/`Chapter` (case-insensitive), optional `.`,
    // whitespace, integer, optional whitespace + dash/colon/em-dash,
    // followed by optional whitespace.
    val regex = Regex("^(?:ch(?:apter)?\\.?)\\s*\\d+\\s*[-:—–]\\s*", RegexOption.IGNORE_CASE)
    val stripped = regex.replaceFirst(title, "").trim()
    // Don't return an empty string — that'd render as a blank row.
    // Fall back to the original if stripping leaves nothing readable.
    return stripped.ifEmpty { title }
}
