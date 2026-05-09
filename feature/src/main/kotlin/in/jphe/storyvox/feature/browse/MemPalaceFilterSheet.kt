package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import `in`.jphe.storyvox.feature.api.MemPalaceFilter
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * MemPalace wing-picker bottom sheet (#191). Single-dimension filter:
 * which wing to scope the listing to. The "All" chip clears the filter
 * and falls Browse back to the wing-less Popular/NewReleases tabs.
 *
 * The wing list is supplied by the caller (BrowseViewModel populates it
 * lazily on first switch to MemPalace via `MemPalaceSource.genres()`).
 * An empty list collapses the sheet to just the "All" chip + an
 * unconfigured-state hint.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemPalaceFilterSheet(
    filter: MemPalaceFilter,
    wings: List<String>,
    onApply: (MemPalaceFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    var local by remember { mutableStateOf(filter) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                "Filter by wing",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            if (wings.isEmpty()) {
                Text(
                    "No wings reachable. Confirm your palace daemon is configured under Settings → Memory Palace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                WingChip(
                    label = "All",
                    selected = local.wing == null,
                    onClick = { local = local.copy(wing = null) },
                )
                wings.forEach { wing ->
                    WingChip(
                        label = prettifyWing(wing),
                        selected = local.wing == wing,
                        onClick = { local = local.copy(wing = wing) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }
                Button(
                    onClick = { onApply(local) },
                    modifier = Modifier.weight(2f),
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun WingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BrassButton(
        label = label,
        onClick = onClick,
        variant = if (selected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
    )
}

/** Capitalise + de-snake_case a wing name for display. Mirrors
 *  `MemPalaceIds.prettify` from `:source-mempalace` (which `:feature`
 *  doesn't depend on) so we don't pull a module dep over a one-liner. */
internal fun prettifyWing(raw: String): String =
    raw.replace('_', ' ')
        .split(' ')
        .joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.uppercase() }
        }
