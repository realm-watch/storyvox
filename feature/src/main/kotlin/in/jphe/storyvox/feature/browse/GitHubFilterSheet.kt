package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import `in`.jphe.storyvox.feature.api.GitHubArchivedStatus
import `in`.jphe.storyvox.feature.api.GitHubPushedSince
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.GitHubSort
import `in`.jphe.storyvox.feature.api.GitHubVisibilityFilter
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * GitHub-shaped filter bottom sheet. Mirrors the qualifier set
 * `GitHubFilterQuery.composeGitHubQuery` consumes: minStars, language,
 * pushedSince, sort. Local state buffers the user's tweaks until they
 * tap Apply so each chip toggle doesn't fire a `/search/repositories`
 * call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubFilterSheet(
    filter: GitHubSearchFilter,
    onApply: (GitHubSearchFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    /** Controls whether the Public/Private/Both visibility chip row is
     *  rendered. The caller passes `true` only when the user has the
     *  `repo` OAuth scope — otherwise `is:private` would silently match
     *  nothing on github.com and the filter would feel broken. */
    showVisibilityChips: Boolean = false,
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
                "Filter GitHub",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            // Minimum stars — slider 0..500. Discrete steps every 10.
            // Above 500 is uncommon for fiction repos and would mask
            // smaller-but-relevant repos. User who wants exact control
            // can supply a precise GitHub URL via the add-by-URL sheet.
            SectionLabel("Minimum stars: ${local.minStars ?: 0}")
            Slider(
                value = (local.minStars ?: 0).toFloat(),
                onValueChange = { v ->
                    val n = v.toInt()
                    local = local.copy(minStars = n.takeIf { it > 0 })
                },
                valueRange = 0f..STARS_MAX,
                steps = STARS_STEPS,
            )

            HorizontalDivider()

            // Language — free-text ISO-639-1 (en, fr, ja, ...). GitHub
            // accepts the human label too ("English") but the code is
            // less ambiguous. Empty = no language qualifier.
            SectionLabel("Language (ISO code, e.g. en, ja)")
            OutlinedTextField(
                value = local.language.orEmpty(),
                onValueChange = { v ->
                    local = local.copy(language = v.trim().takeIf { it.isNotEmpty() })
                },
                placeholder = { Text("e.g. en") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            SectionLabel("Last pushed")
            PushedSinceDropdown(
                value = local.pushedSince,
                onChange = { local = local.copy(pushedSince = it) },
            )

            HorizontalDivider()

            // Topic tags (#205). Free-text comma-separated input, parsed
            // into a Set<String> on every keystroke. Each tag emits a
            // `topic:X` qualifier; multiple AND together. We avoid a
            // chip-based picker for v1 because GitHub has no public
            // topic-suggestion API — users type what they know.
            SectionLabel("Topic tags (comma-separated, e.g. litrpg, fantasy)")
            var tagsDraft by remember(local.tags) { mutableStateOf(local.tags.joinToString(", ")) }
            OutlinedTextField(
                value = tagsDraft,
                onValueChange = { v ->
                    tagsDraft = v
                    local = local.copy(
                        tags = v.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                    )
                },
                placeholder = { Text("e.g. litrpg, fantasy") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // Archive status (#205). Default `Any` returns both kinds;
            // ActiveOnly is the most common power-user filter (skip
            // deprecated forks); ArchivedOnly is for retro-archeology.
            SectionLabel("Repository state")
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ArchivedChip("Any", local.archivedStatus == GitHubArchivedStatus.Any) {
                    local = local.copy(archivedStatus = GitHubArchivedStatus.Any)
                }
                ArchivedChip("Active only", local.archivedStatus == GitHubArchivedStatus.ActiveOnly) {
                    local = local.copy(archivedStatus = GitHubArchivedStatus.ActiveOnly)
                }
                ArchivedChip("Archived only", local.archivedStatus == GitHubArchivedStatus.ArchivedOnly) {
                    local = local.copy(archivedStatus = GitHubArchivedStatus.ArchivedOnly)
                }
            }

            // Visibility (#204) — only meaningful with the `repo` scope.
            // Without it, GitHub returns no private hits anyway, so we
            // hide the row entirely rather than offering a knob that
            // silently empties the grid.
            if (showVisibilityChips) {
                HorizontalDivider()
                SectionLabel("Visibility")
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    ArchivedChip("Both", local.visibility == GitHubVisibilityFilter.Both) {
                        local = local.copy(visibility = GitHubVisibilityFilter.Both)
                    }
                    ArchivedChip("Public", local.visibility == GitHubVisibilityFilter.PublicOnly) {
                        local = local.copy(visibility = GitHubVisibilityFilter.PublicOnly)
                    }
                    ArchivedChip("Private", local.visibility == GitHubVisibilityFilter.PrivateOnly) {
                        local = local.copy(visibility = GitHubVisibilityFilter.PrivateOnly)
                    }
                }
            }

            HorizontalDivider()

            SectionLabel("Sort by")
            SortDropdown(
                value = local.sort,
                onChange = { local = local.copy(sort = it) },
            )

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
private fun ArchivedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BrassButton(
        label = label,
        onClick = onClick,
        variant = if (selected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PushedSinceDropdown(
    value: GitHubPushedSince,
    onChange: (GitHubPushedSince) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value.label,
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
            GitHubPushedSince.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(
    value: GitHubSort,
    onChange: (GitHubSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value.label,
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
            GitHubSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val STARS_MAX = 500f
private const val STARS_STEPS = 49 // every 10 stars in 0..500

private val GitHubPushedSince.label: String
    get() = when (this) {
        GitHubPushedSince.Any -> "Any time"
        GitHubPushedSince.Last7Days -> "Last 7 days"
        GitHubPushedSince.Last30Days -> "Last 30 days"
        GitHubPushedSince.Last90Days -> "Last 90 days"
        GitHubPushedSince.LastYear -> "Last year"
    }

private val GitHubSort.label: String
    get() = when (this) {
        GitHubSort.BestMatch -> "Best match"
        GitHubSort.Stars -> "Stars"
        GitHubSort.Updated -> "Recently updated"
    }
