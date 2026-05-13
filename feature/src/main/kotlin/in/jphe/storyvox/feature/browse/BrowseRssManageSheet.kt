package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.SuggestedFeed
import `in`.jphe.storyvox.feature.api.SuggestedFeedKind
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #247 — RSS feed management bottom sheet, opened by the FAB on
 * Browse → RSS. Replaces the inline "RSS feeds" rows that used to
 * live in Settings → Library & Sync (the source on/off toggle stays
 * in Settings — that's a different control).
 *
 * Three sections:
 *  1. Add by URL — `OutlinedTextField` + `Add` button.
 *  2. Subscribed feeds — flat list with per-row `Remove` action.
 *  3. Suggested feeds — collapsible curated list grouped by category,
 *     one-tap subscribe.
 *
 * Voice and rhythm mirror the existing `AddByUrlSheet` for Library so
 * the two add-affordances feel like the same family — the user is
 * paste-something-and-tap-Add in both surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseRssManageSheet(
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val subs by viewModel.rssSubscriptions.collectAsStateWithLifecycle()
    val suggested by viewModel.suggestedRssFeeds.collectAsStateWithLifecycle()

    var draftUrl by remember { mutableStateOf("") }
    var suggestionsExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                "Manage RSS feeds",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Text(
                "Subscribe to any feed that publishes RSS or Atom. Storyvox treats each feed as one fiction; each item is a chapter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Add by URL ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                BrassButton(
                    label = "Add",
                    onClick = {
                        val trimmed = draftUrl.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.addRssFeed(trimmed)
                            draftUrl = ""
                        }
                    },
                    variant = BrassButtonVariant.Primary,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

            // ── Subscribed feeds ─────────────────────────────────────
            Text(
                text = if (subs.isEmpty()) "No subscriptions yet" else "Your feeds (${subs.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subs.isEmpty()) {
                Text(
                    "Paste a feed URL above to subscribe, or pick from the suggested list below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                subs.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { viewModel.removeRssFeedByUrl(url) }) {
                            Text("Remove")
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

            // ── Suggested feeds (collapsible) ────────────────────────
            // Collapsed by default so users who already have their own
            // feeds don't trip over a long curated list. Tap header to
            // expand.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { suggestionsExpanded = !suggestionsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (suggestionsExpanded) "▾  Suggested feeds" else "▸  Suggested feeds",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!suggestionsExpanded) {
                Text(
                    text = "Tap to browse curated feeds (Buddhist & dharma, more coming).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SuggestedFeedsList(
                    suggested = suggested,
                    canonicalSubs = subs.map { it.lowercase() }.toSet(),
                    onAdd = viewModel::addRssFeed,
                )
            }
        }
    }
}

@Composable
private fun SuggestedFeedsList(
    suggested: List<SuggestedFeed>,
    canonicalSubs: Set<String>,
    onAdd: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val byCategory = suggested.groupBy { it.category }
    byCategory.forEach { (category, suggestions) ->
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm, bottom = 2.dp),
        )
        suggestions.forEach { feed ->
            val alreadyAdded = feed.url.lowercase() in canonicalSubs
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = feed.title,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = feed.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (feed.kind) {
                                SuggestedFeedKind.Text ->
                                    "Text articles — narrate well"
                                SuggestedFeedKind.AudioPodcast ->
                                    "Audio podcast — storyvox narrates show notes only"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    if (alreadyAdded) {
                        Text(
                            text = "Added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = spacing.sm, end = spacing.sm),
                        )
                    } else {
                        BrassButton(
                            label = "Add",
                            onClick = { onAdd(feed.url) },
                            variant = BrassButtonVariant.Text,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
        }
    }
}
