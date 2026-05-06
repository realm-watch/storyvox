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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.UiContentWarning
import `in`.jphe.storyvox.feature.api.UiFictionStatus
import `in`.jphe.storyvox.feature.api.UiFictionType
import `in`.jphe.storyvox.feature.api.UiSearchOrder
import `in`.jphe.storyvox.feature.api.UiSortDirection
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Royal Road filter bottom sheet. Mirrors `/fictions/search` form. Local state
 * lets the user tweak many knobs before tapping Apply, so the search isn't
 * hammered on every chip toggle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseFilterSheet(
    filter: BrowseFilter,
    onApply: (BrowseFilter) -> Unit,
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
                "Filter Royal Road",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            // Sort by
            SortRow(
                orderBy = local.orderBy,
                direction = local.direction,
                onOrderBy = { local = local.copy(orderBy = it) },
                onDirection = { local = local.copy(direction = it) },
            )

            HorizontalDivider()

            // Status
            SectionLabel("Status")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                UiFictionStatus.entries.forEach { status ->
                    val selected = status in local.statuses
                    FilterChip(
                        selected = selected,
                        onClick = {
                            local = local.copy(
                                statuses = if (selected) local.statuses - status else local.statuses + status,
                            )
                        },
                        label = { Text(status.label) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            // Type
            SectionLabel("Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                UiFictionType.entries.forEach { type ->
                    FilterChip(
                        selected = local.type == type,
                        onClick = { local = local.copy(type = type) },
                        label = { Text(type.label) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            HorizontalDivider()

            // Tags include
            SectionLabel("Include tags")
            TagSelector(
                selected = local.tagsInclude,
                onChanged = { local = local.copy(tagsInclude = it) },
            )

            // Tags exclude
            SectionLabel("Exclude tags")
            TagSelector(
                selected = local.tagsExclude,
                onChanged = { local = local.copy(tagsExclude = it) },
            )

            HorizontalDivider()

            // Length (pages)
            SectionLabel("Length (pages): ${local.pagesLabel()}")
            val pagesRange = (local.minPages?.toFloat() ?: 0f)..(local.maxPages?.toFloat() ?: PAGES_MAX)
            RangeSlider(
                value = pagesRange,
                onValueChange = { range ->
                    local = local.copy(
                        minPages = range.start.toInt().takeIf { it > 0 },
                        maxPages = range.endInclusive.toInt().takeIf { it < PAGES_MAX },
                    )
                },
                valueRange = 0f..PAGES_MAX,
                steps = 0,
            )

            // Rating
            SectionLabel("Rating: ${local.ratingLabel()}")
            val ratingRange = (local.minRating ?: 0f)..(local.maxRating ?: 5f)
            RangeSlider(
                value = ratingRange,
                onValueChange = { range ->
                    local = local.copy(
                        minRating = range.start.takeIf { it > 0f },
                        maxRating = range.endInclusive.takeIf { it < 5f },
                    )
                },
                valueRange = 0f..5f,
                steps = 9,
            )

            HorizontalDivider()

            // Content warnings
            SectionLabel("Content warnings — exclude")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                UiContentWarning.entries.forEach { warning ->
                    val selected = warning in local.warningsExclude
                    FilterChip(
                        selected = selected,
                        onClick = {
                            local = local.copy(
                                warningsExclude = if (selected) local.warningsExclude - warning else local.warningsExclude + warning,
                                // Mutually exclusive with require — toggling exclude clears require for the same warning.
                                warningsRequire = local.warningsRequire - warning,
                            )
                        },
                        label = { Text(warning.label) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            // Apply / Reset buttons
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SortRow(
    orderBy: UiSearchOrder,
    direction: UiSortDirection,
    onOrderBy: (UiSearchOrder) -> Unit,
    onDirection: (UiSortDirection) -> Unit,
) {
    val spacing = LocalSpacing.current
    SectionLabel("Sort by")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(2f),
        ) {
            OutlinedTextField(
                value = orderBy.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                UiSearchOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = {
                            onOrderBy(order)
                            expanded = false
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = direction == UiSortDirection.Desc,
            onClick = {
                onDirection(if (direction == UiSortDirection.Desc) UiSortDirection.Asc else UiSortDirection.Desc)
            },
            label = { Text(if (direction == UiSortDirection.Desc) "Desc" else "Asc") },
            colors = brassFilterChipColors(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSelector(
    selected: Set<String>,
    onChanged: (Set<String>) -> Unit,
) {
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        if (query.isBlank()) RoyalRoadTags.ALL.take(24)
        else RoyalRoadTags.ALL.filter { it.contains(query, ignoreCase = true) }.take(40)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Filter tags") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
    if (selected.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier.padding(top = spacing.xs),
        ) {
            selected.forEach { tag ->
                FilterChip(
                    selected = true,
                    onClick = { onChanged(selected - tag) },
                    label = { Text(tag.replace('_', ' ')) },
                    colors = brassFilterChipColors(),
                )
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.padding(top = spacing.xs),
    ) {
        matches.forEach { tag ->
            val isSelected = tag in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    onChanged(if (isSelected) selected - tag else selected + tag)
                },
                label = { Text(tag.replace('_', ' ')) },
                colors = brassFilterChipColors(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

private const val PAGES_MAX = 3000f

private fun BrowseFilter.pagesLabel(): String {
    val lo = minPages ?: 0
    val hi = maxPages ?: PAGES_MAX.toInt()
    return "$lo – $hi"
}

private fun BrowseFilter.ratingLabel(): String {
    val lo = "%.1f".format(minRating ?: 0f)
    val hi = "%.1f".format(maxRating ?: 5f)
    return "$lo – $hi"
}

private val UiFictionStatus.label: String
    get() = when (this) {
        UiFictionStatus.Ongoing -> "Ongoing"
        UiFictionStatus.Completed -> "Completed"
        UiFictionStatus.Hiatus -> "Hiatus"
        UiFictionStatus.Stub -> "Stub"
        UiFictionStatus.Dropped -> "Dropped"
    }

private val UiFictionType.label: String
    get() = when (this) {
        UiFictionType.All -> "All"
        UiFictionType.Original -> "Original"
        UiFictionType.FanFiction -> "Fan Fiction"
    }

private val UiContentWarning.label: String
    get() = when (this) {
        UiContentWarning.Profanity -> "Profanity"
        UiContentWarning.SexualContent -> "Sexual"
        UiContentWarning.GraphicViolence -> "Violence"
        UiContentWarning.SensitiveContent -> "Sensitive"
        UiContentWarning.AiAssisted -> "AI-assisted"
        UiContentWarning.AiGenerated -> "AI-generated"
    }

private val UiSearchOrder.label: String
    get() = when (this) {
        UiSearchOrder.Relevance -> "Relevance"
        UiSearchOrder.Popularity -> "Popularity"
        UiSearchOrder.Rating -> "Average rating"
        UiSearchOrder.LastUpdate -> "Last update"
        UiSearchOrder.ReleaseDate -> "Release date"
        UiSearchOrder.Followers -> "Followers"
        UiSearchOrder.Length -> "Length"
        UiSearchOrder.Views -> "Views"
        UiSearchOrder.Title -> "Title"
    }
