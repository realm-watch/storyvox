package `in`.jphe.storyvox.feature.settings.plugins

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plugin manager (#404) — filter chips for the on/off/all view state.
 *
 * - [On]: only plugins the user has enabled (or default-enabled and
 *   not explicitly disabled).
 * - [Off]: only plugins the user has explicitly disabled (or that
 *   default to off and haven't been flipped on).
 * - [All]: every registered plugin.
 */
enum class PluginFilterChip { On, Off, All }

/**
 * Plugin manager (#404) — UI row for a single plugin.
 *
 * Wraps a [SourcePluginDescriptor] with the resolved enabled state so
 * the screen doesn't have to re-look-up the per-id boolean during
 * rendering. The descriptor lives in `:core-data`; this is the
 * `:feature/settings/plugins` projection.
 */
@Immutable
data class PluginManagerRow(
    val descriptor: SourcePluginDescriptor,
    val enabled: Boolean,
)

/** Plugin manager (#404) — Compose-facing UI state. */
@Immutable
data class PluginManagerUiState(
    /** All plugins, regardless of filter/search — the section
     *  renderer slices this by category and then filters by the
     *  search/chip state. */
    val plugins: List<PluginManagerRow> = emptyList(),
    val searchQuery: String = "",
    val filterChip: PluginFilterChip = PluginFilterChip.All,
)

/**
 * Plugin manager (#404) — ViewModel.
 *
 * Iterates `SourcePluginRegistry.descriptors`, joining each with the
 * user's `sourcePluginsEnabled` map (with the plugin's
 * `defaultEnabled` as fallback for unseeded ids). The Compose tree
 * filters by category + search + chip state at render time; the
 * state object stays a single source of truth and the screen handles
 * the three filter axes without needing further ViewModel state.
 */
@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val registry: SourcePluginRegistry,
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _filterChip = MutableStateFlow(PluginFilterChip.All)

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val filterChip: StateFlow<PluginFilterChip> = _filterChip.asStateFlow()

    private val pluginsFlow: kotlinx.coroutines.flow.Flow<List<PluginManagerRow>> =
        settings.settings.map { s ->
            registry.descriptors.map { descriptor ->
                val explicit = s.sourcePluginsEnabled[descriptor.id]
                val effective = explicit ?: descriptor.defaultEnabled
                PluginManagerRow(descriptor = descriptor, enabled = effective)
            }
        }

    val uiState: StateFlow<PluginManagerUiState> = combine(
        pluginsFlow, _searchQuery, _filterChip,
    ) { plugins, query, chip ->
        PluginManagerUiState(
            plugins = plugins,
            searchQuery = query,
            filterChip = chip,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PluginManagerUiState())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilterChip(chip: PluginFilterChip) { _filterChip.value = chip }

    fun togglePlugin(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setSourcePluginEnabled(id, enabled) }
    }
}

/**
 * Plugin manager (#404) — applies the search + chip filter to a list
 * of plugins. Pulled out into a free function so the screen can call
 * it once per section.
 *
 * Search is a substring match on `displayName`, `description`, and
 * `id` (so a user looking for "wiki" matches Wikipedia and Wikisource
 * but not "Wikimedia" in another field; the substring matches at
 * least one of the three). Case-insensitive.
 *
 * Filter chip:
 * - [PluginFilterChip.On] keeps enabled plugins.
 * - [PluginFilterChip.Off] keeps disabled plugins.
 * - [PluginFilterChip.All] keeps everything.
 */
fun filterPlugins(
    plugins: List<PluginManagerRow>,
    query: String,
    chip: PluginFilterChip,
): List<PluginManagerRow> {
    val byChip = when (chip) {
        PluginFilterChip.On -> plugins.filter { it.enabled }
        PluginFilterChip.Off -> plugins.filterNot { it.enabled }
        PluginFilterChip.All -> plugins
    }
    if (query.isBlank()) return byChip
    val needle = query.trim().lowercase()
    return byChip.filter { row ->
        row.descriptor.displayName.lowercase().contains(needle) ||
            row.descriptor.description.lowercase().contains(needle) ||
            row.descriptor.id.lowercase().contains(needle)
    }
}

/**
 * Plugin manager (#404) — group [plugins] into the manager's three
 * section buckets: Fiction sources (every Text + Ebook + Database +
 * Other category), Audio streams (AudioStream category), and the
 * Voice bundles placeholder section.
 *
 * Voice bundles aren't `@SourcePlugin`-annotated yet (the v2 voice
 * bundle registry is a follow-up issue); they render as a "Coming in
 * v2" placeholder, so the bucket is always empty in v1.
 */
fun groupPluginsForManager(plugins: List<PluginManagerRow>): PluginManagerSections {
    val fiction = plugins.filter { it.descriptor.category != SourceCategory.AudioStream }
    val audio = plugins.filter { it.descriptor.category == SourceCategory.AudioStream }
    return PluginManagerSections(fiction = fiction, audio = audio, voiceBundles = emptyList())
}

/** Plugin manager (#404) — three category sections for rendering. */
@Immutable
data class PluginManagerSections(
    val fiction: List<PluginManagerRow>,
    val audio: List<PluginManagerRow>,
    val voiceBundles: List<PluginManagerRow>,
)
