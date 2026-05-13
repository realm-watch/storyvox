package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #116 — bottom sheet opened by long-pressing a library card.
 * One row per [Shelf] with a toggle; flipping a toggle calls
 * [onToggle] which adds or removes the membership through
 * [LibraryViewModel.toggleShelf].
 *
 * Display labels come from [Shelf.displayName] — the data-layer owns
 * the user-facing strings so every shelf-aware surface shows the same
 * word (chip row, sheet, future "where is this book?" hint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageShelvesSheet(
    state: ManageShelvesSheetState,
    onToggle: (String, Shelf) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is ManageShelvesSheetState.Open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "Manage shelves",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.fictionTitle.isNotBlank()) {
                Text(
                    text = state.fictionTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Shelf.ALL.forEach { shelf ->
                val isMember = shelf in state.memberOf
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = shelf.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = isMember,
                        onCheckedChange = { onToggle(state.fictionId, shelf) },
                    )
                }
            }
        }
    }
}
