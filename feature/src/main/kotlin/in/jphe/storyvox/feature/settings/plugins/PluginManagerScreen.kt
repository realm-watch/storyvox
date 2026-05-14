package `in`.jphe.storyvox.feature.settings.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Plugin manager screen (#404) — Settings → Plugins.
 *
 * Registry-driven brass-edged card list. Each card has:
 *  - Brass monogram icon (`fictionMonogram(displayName)`).
 *  - Display name + plugin description (subtitle).
 *  - Capability chips (Follow, Search, Audio, Anonymous/PAT).
 *  - Brass-edged switch.
 *  - Tap to open details sheet.
 *
 * Three category sections: Fiction sources, Audio streams, Voice
 * bundles (placeholder for v2).
 *
 * The top of the screen has a search input + 3 filter chips
 * (On / Off / All). Search is substring on displayName/description/id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: PluginManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    var detailsForId by remember { mutableStateOf<String?>(null) }

    val sections = remember(state.plugins) { groupPluginsForManager(state.plugins) }
    val fictionVisible = remember(sections.fiction, state.searchQuery, state.filterChip) {
        filterPlugins(sections.fiction, state.searchQuery, state.filterChip)
    }
    val audioVisible = remember(sections.audio, state.searchQuery, state.filterChip) {
        filterPlugins(sections.audio, state.searchQuery, state.filterChip)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Plugins",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Search input
            item("search") {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text("Search plugins") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Filter chips: On / Off / All
            item("chips") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.On,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.On) },
                        label = { Text("On") },
                    )
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.Off,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.Off) },
                        label = { Text("Off") },
                    )
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.All,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.All) },
                        label = { Text("All") },
                    )
                }
            }

            // Fiction sources section
            item("fiction-header") {
                CategoryHeader(
                    title = "Fiction sources",
                    count = fictionVisible.size,
                )
            }
            items(fictionVisible) { row ->
                PluginCard(
                    row = row,
                    onToggle = { enabled -> viewModel.togglePlugin(row.descriptor.id, enabled) },
                    onTap = { detailsForId = row.descriptor.id },
                )
            }

            // Audio streams section
            item("audio-header") {
                CategoryHeader(
                    title = "Audio streams",
                    count = audioVisible.size,
                )
            }
            items(audioVisible) { row ->
                PluginCard(
                    row = row,
                    onToggle = { enabled -> viewModel.togglePlugin(row.descriptor.id, enabled) },
                    onTap = { detailsForId = row.descriptor.id },
                )
            }

            // Voice bundles — v2 placeholder
            item("voice-header") {
                CategoryHeader(
                    title = "Voice bundles",
                    count = 0,
                )
            }
            item("voice-coming-soon") {
                Text(
                    "Coming in v2: voice bundle registry. Voice files will surface here as " +
                        "`@SourcePlugin`-style annotations land.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.sm),
                )
            }
        }
    }

    // Details sheet
    val detailRow = state.plugins.firstOrNull { it.descriptor.id == detailsForId }
    if (detailRow != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { detailsForId = null },
            sheetState = sheetState,
        ) {
            PluginDetailsContent(row = detailRow)
        }
    }
}

@Composable
private fun CategoryHeader(title: String, count: Int) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(vertical = spacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (count == 1) "1 plugin" else "$count plugins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Plugin manager card (#404). Brass-edged outline, brass monogram on
 * the left, name + description in the middle, switch on the right,
 * capability chips below, "tap for details" hint at the bottom.
 *
 * The whole card is clickable for the details sheet; the switch
 * intercepts taps so toggling doesn't open the details sheet.
 */
@Composable
private fun PluginCard(
    row: PluginManagerRow,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = brass,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = brass,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (row.enabled) brass else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onTap)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Brass monogram icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fictionMonogram(row.descriptor.displayName, row.descriptor.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.descriptor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (row.descriptor.description.isNotBlank()) {
                    Text(
                        row.descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = brassColors,
            )
        }
        // Capability chips
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (row.descriptor.supportsSearch) {
                CapabilityChip("Search")
            }
            if (row.descriptor.supportsFollow) {
                CapabilityChip("Follow")
            }
            if (row.descriptor.category == `in`.jphe.storyvox.data.source.plugin.SourceCategory.AudioStream) {
                CapabilityChip("Audio")
            } else {
                CapabilityChip("Text")
            }
        }
        Text(
            "Status: ${if (row.enabled) "enabled" else "disabled"} · Tap for details",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityChip(label: String) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
private fun PluginDetailsContent(row: PluginManagerRow) {
    val spacing = LocalSpacing.current
    val descriptor: SourcePluginDescriptor = row.descriptor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            descriptor.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (descriptor.description.isNotBlank()) {
            Text(
                descriptor.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
        Text("Capabilities", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            CapabilityChip(if (descriptor.supportsSearch) "Search ✓" else "Search ✗")
            CapabilityChip(if (descriptor.supportsFollow) "Follow ✓" else "Follow ✗")
            CapabilityChip(
                if (descriptor.category == `in`.jphe.storyvox.data.source.plugin.SourceCategory.AudioStream) "Audio" else "Text",
            )
        }
        if (descriptor.sourceUrl.isNotBlank()) {
            Text(
                "Source: ${descriptor.sourceUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Plugin id: ${descriptor.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(spacing.md))
    }
}
