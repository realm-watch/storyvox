package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceEngineId
import `in`.jphe.storyvox.playback.voice.VoiceFavorites
import `in`.jphe.storyvox.playback.voice.VoiceLibraryCollapse
import `in`.jphe.storyvox.playback.voice.VoiceLibrarySection
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class VoiceLibraryUiState(
    /** User-starred voices, surfaced under their own header at the top.
     *  Empty when nothing pinned, in which case the whole section is
     *  hidden by the screen. The internal name "favorites" is preserved
     *  from #89 (so is the DataStore key `voice_favorites_v1`) — only
     *  the user-facing label changed to "Starred" in #106.
     *
     *  The legacy "Featured" header above this section was removed in
     *  #129 — the curated [VoiceCatalog.featuredIds] still feeds the
     *  first-launch [VoicePickerGate], but the Voice Library now lets
     *  those voices appear in their natural Engine → Tier home alongside
     *  every other voice rather than re-pinning them to a top section. */
    val favorites: List<UiVoiceInfo> = emptyList(),
    /** Installed voices grouped first by engine (Piper, then Kokoro)
     *  and then by tier within each engine. Within Piper the tier order
     *  is **ascending** (Low → Medium → High) — the engine choice is the
     *  coarse bucket and users typically want to scroll past lighter
     *  Piper voices on the way to the heavier ones. Within Kokoro the
     *  Studio tier leads (Kokoro-exclusive) followed by the rest. Both
     *  the outer and inner maps are iteration-ordered so the screen can
     *  render straight through. Empty engine and tier groups are
     *  dropped. See [groupByEngineThenTier]. */
    val installedByEngine: Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = emptyMap(),
    /** Available (not-yet-downloaded) voices grouped by engine then
     *  tier. Same ordering contract as [installedByEngine]. */
    val availableByEngine: Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = emptyMap(),
    val favoriteIds: Set<String> = emptySet(),
    val activeVoiceId: String? = null,
    val currentDownload: DownloadingVoice? = null,
    val pendingDelete: UiVoiceInfo? = null,
    val errorMessage: String? = null,
    /** Engine sub-headers currently rendered as **collapsed** (#130).
     *  Derived from [VoiceLibraryCollapse]'s flipped set + per-section
     *  default policy in the ViewModel — the screen only needs to ask
     *  "is this (section, engine) collapsed?" without knowing the
     *  default rules. Empty on first launch for the Installed section
     *  (defaults to expanded); will already include every Available
     *  engine on first paint (Available defaults to collapsed). */
    val collapsedEngines: Set<EngineKey> = emptySet(),
)

@Immutable
data class DownloadingVoice(
    val voiceId: String,
    /** Null while the download is still resolving / no total known. */
    val progress: Float?,
)

@HiltViewModel
class VoiceLibraryViewModel @Inject constructor(
    private val voiceManager: VoiceManager,
    private val voiceFavorites: VoiceFavorites,
    private val voiceLibraryCollapse: VoiceLibraryCollapse,
    /** PR-4 (#183) — Azure rows in the picker are tappable iff a key
     *  is configured. We observe the settings flow's `azure.isConfigured`
     *  bit and project Azure entries from `availableVoices` into the
     *  installed bucket when true, so the existing activate path Just
     *  Works. The actual key + region stay encrypted in
     *  [AzureCredentials]; this VM only needs the boolean. */
    private val settingsRepo: SettingsRepositoryUi,
) : ViewModel() {

    private val _currentDownload = MutableStateFlow<DownloadingVoice?>(null)
    private val _pendingDelete = MutableStateFlow<UiVoiceInfo?>(null)
    private val _error = MutableStateFlow<String?>(null)

    private val azureKeyConfigured = settingsRepo.settings.map { it.azure.isConfigured }

    val uiState: StateFlow<VoiceLibraryUiState> = combine(
        voiceManager.installedVoices,
        flowOf(voiceManager.availableVoices),
        voiceManager.activeVoice,
        voiceFavorites.favoriteIds,
        // Pack the three local mutable sources + collapse-store flipped
        // set + azure-configured bit into one combined flow so the outer
        // combine fits in 5 slots. The Azure bit lives here rather than
        // as its own outer slot specifically because adding a 6th outer
        // slot would push us past kotlinx.coroutines's typed combine
        // ceiling and force a less-typed Iterable<Flow<*>> path.
        combine(
            _currentDownload.asStateFlow(),
            _pendingDelete.asStateFlow(),
            _error.asStateFlow(),
            voiceLibraryCollapse.flippedKeys,
            azureKeyConfigured,
        ) { d, p, e, flipped, azureConfigured ->
            CollapsedAndLocal(d, p, e, flipped, azureConfigured)
        },
    ) { installedFromManager, available, active, favIds, locals ->
        // PR-4: when an Azure key is configured, project all Azure
        // catalog entries into the installed bucket so the existing
        // "tappable iff installed" picker logic activates them. Without
        // a key, Azure rows stay in availableByEngine and the user
        // sees them greyed out as "available but not yet downloaded"
        // — onRowTapped surfaces a friendly "configure key in Settings"
        // message instead of attempting download() (which would throw).
        val installed = if (locals.azureConfigured) {
            installedFromManager + available
                .filter { it.engineType is EngineType.Azure }
                .map { it.copy(isInstalled = true) }
        } else {
            installedFromManager
        }
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        // Favourites pulls from installed first (preserving the
        // installed-flag) and falls back to the catalog for voices the
        // user pinned but hasn't downloaded yet. Listed favourites are
        // EXCLUDED from the per-tier sections below to avoid showing the
        // same row twice. (#129 removed the parallel exclusion for the
        // legacy Featured set — those voices now flow into their natural
        // Engine → Tier section like any other catalog entry.)
        val favorites = favIds.mapNotNull { id ->
            installed.firstOrNull { it.id == id }
                ?: available.firstOrNull { it.id == id }
        }
        val installedFiltered = installed.filterNot { it.id in favIds }
        val availableFiltered = available.filterNot {
            it.id in installedIds || it.id in favIds
        }
        val installedGrouped = installedFiltered.groupByEngineThenTier()
        val availableGrouped = availableFiltered.groupByEngineThenTier()
        VoiceLibraryUiState(
            favorites = favorites,
            installedByEngine = installedGrouped,
            availableByEngine = availableGrouped,
            favoriteIds = favIds,
            activeVoiceId = active?.id,
            currentDownload = locals.download,
            pendingDelete = locals.pendingDelete,
            errorMessage = locals.error,
            collapsedEngines = computeCollapsedEngines(
                installedEngines = installedGrouped.keys,
                availableEngines = availableGrouped.keys,
                flipped = locals.flipped,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceLibraryUiState())

    private var downloadJob: Job? = null

    fun toggleFavorite(voiceId: String) {
        viewModelScope.launch { voiceFavorites.toggle(voiceId) }
    }

    /** User tapped an engine sub-header — flip its collapsed state in
     *  the persisted store. The new state propagates to [uiState] via
     *  the [voiceLibraryCollapse.flippedKeys] flow combined into the
     *  main pipeline; the screen re-renders with rows hidden/shown. */
    fun toggleEngineCollapsed(section: VoiceLibrarySection, engine: VoiceEngine) {
        val key = EngineKey(section, engine.toCoreId())
        viewModelScope.launch { voiceLibraryCollapse.toggle(key) }
    }

    fun onRowTapped(voice: UiVoiceInfo) {
        // PR-4 (#183) — Azure voices activate when a key is configured;
        // otherwise the row is greyed out (not isInstalled) and tapping
        // surfaces a "configure key in Settings" pointer rather than
        // hitting download() (which throws by design — VoiceManager
        // rejects Azure download attempts so a future regression in
        // this branch can't quietly start hammering Azure for a
        // missing model).
        if (voice.engineType is EngineType.Azure) {
            if (voice.isInstalled) {
                activate(voice.id)
            } else {
                _error.value =
                    "Configure your Azure Speech key in " +
                    "Settings → Cloud voices to use this voice."
            }
            return
        }
        if (voice.isInstalled) activate(voice.id) else download(voice.id)
    }

    fun activate(voiceId: String) {
        viewModelScope.launch { voiceManager.setActive(voiceId) }
    }

    fun download(voiceId: String) {
        if (_currentDownload.value != null) return
        _currentDownload.value = DownloadingVoice(voiceId, progress = null)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                voiceManager.download(voiceId).collect { p ->
                    when (p) {
                        is VoiceManager.DownloadProgress.Resolving -> {
                            _currentDownload.value = DownloadingVoice(voiceId, progress = null)
                        }
                        is VoiceManager.DownloadProgress.Downloading -> {
                            val frac = if (p.totalBytes > 0L) {
                                (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                            } else null
                            _currentDownload.value = DownloadingVoice(voiceId, frac)
                        }
                        is VoiceManager.DownloadProgress.Done -> Unit
                        is VoiceManager.DownloadProgress.Failed -> {
                            _error.value = p.reason
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // User-driven cancel via cancelDownload(). Don't surface as
                // an error — the row already disappears via the finally
                // block. Re-throw so structured concurrency unwinds cleanly.
                throw ce
            } catch (t: Throwable) {
                _error.value = t.message ?: "Download failed"
            } finally {
                _currentDownload.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _currentDownload.value = null
    }

    fun requestDelete(voice: UiVoiceInfo) {
        _pendingDelete.value = voice
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val voice = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { voiceManager.delete(voice.id) }
    }

    fun dismissError() {
        _error.value = null
    }
}

/** Engine grouping discriminator used by the voice library UI. The
 *  underlying [EngineType] is sealed and Kokoro carries a speakerId we
 *  don't want to key on, so we collapse to a tag-only enum here. Order
 *  matters: this is the outer iteration order in [groupByEngineThenTier]
 *  — Piper section first, then Kokoro, then Azure. Azure goes last
 *  intentionally per Solara's spec — Local engines (no cost, no network)
 *  surface above the cloud section, even though Azure's quality tier is
 *  Studio. The visual cue (engine header) matters more than the tier
 *  sort here: users should reach for a free local voice before
 *  considering a paid cloud voice.
 *
 *  Mirrored as [VoiceEngineId] in core-playback for the collapse store
 *  (which lives in core so it can be Hilt-injected without dragging the
 *  feature module). The two enums are kept in lockstep — see
 *  [toCoreId]. */
enum class VoiceEngine { Piper, Kokoro, Azure }

internal fun VoiceEngine.toCoreId(): VoiceEngineId = when (this) {
    VoiceEngine.Piper -> VoiceEngineId.Piper
    VoiceEngine.Kokoro -> VoiceEngineId.Kokoro
    VoiceEngine.Azure -> VoiceEngineId.Azure
}

internal fun VoiceEngineId.toFeatureEngine(): VoiceEngine = when (this) {
    VoiceEngineId.Piper -> VoiceEngine.Piper
    VoiceEngineId.Kokoro -> VoiceEngine.Kokoro
    VoiceEngineId.Azure -> VoiceEngine.Azure
}

private fun UiVoiceInfo.voiceEngine(): VoiceEngine = when (engineType) {
    is EngineType.Piper -> VoiceEngine.Piper
    is EngineType.Kokoro -> VoiceEngine.Kokoro
    is EngineType.Azure -> VoiceEngine.Azure
}

/** Tuple holding the four "local + collapse" flow values that get
 *  packed into a single nested combine slot — the outer combine is
 *  capped at 5 sources, so we group these here instead of using
 *  positional destructuring. Named for readability at the call site. */
private data class CollapsedAndLocal(
    val download: DownloadingVoice?,
    val pendingDelete: UiVoiceInfo?,
    val error: String?,
    val flipped: Set<String>,
    /** PR-4 (#183) — projected from `settings.azure.isConfigured`.
     *  Drives whether Azure rows in the picker are tappable. */
    val azureConfigured: Boolean,
)

/** Compute the rendered collapsed-engines set from the persisted
 *  flipped keys + the engines actually present in each section. We
 *  only emit keys for engines the user can see — there's no point
 *  carrying "available:Piper" if the user has installed every Piper
 *  voice. The screen treats `key in collapsedEngines` as the source
 *  of truth for whether to skip emission of tier rows. */
internal fun computeCollapsedEngines(
    installedEngines: Set<VoiceEngine>,
    availableEngines: Set<VoiceEngine>,
    flipped: Set<String>,
): Set<EngineKey> {
    val out = mutableSetOf<EngineKey>()
    for (engine in installedEngines) {
        val key = EngineKey(VoiceLibrarySection.Installed, engine.toCoreId())
        if (VoiceLibraryCollapse.isCollapsed(key, flipped)) out += key
    }
    for (engine in availableEngines) {
        val key = EngineKey(VoiceLibrarySection.Available, engine.toCoreId())
        if (VoiceLibraryCollapse.isCollapsed(key, flipped)) out += key
    }
    return out
}

/** Tier order **within Piper** — ascending (Low → Medium → High). Piper
 *  has no Studio voice today, but if one ever lands it falls below High
 *  (the slot is left out of this list). The ascending sort matches JP's
 *  ask in #94: Piper users tend to start light and scale up, so showing
 *  "Low" first keeps the lighter-weight voices visible without scroll. */
private val PIPER_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Low,
    QualityLevel.Medium,
    QualityLevel.High,
)

/** Tier order **within Kokoro** — Studio first (the curated peak,
 *  Kokoro-exclusive), then High, then Medium/Low if upstream ever
 *  introduces them. Kokoro voices all share one bundle so tier here is
 *  about quality grade rather than model size; Studio leading is the
 *  point of the section. */
private val KOKORO_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Studio,
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

/** Tier order **within Azure** — every Azure HD voice we ship is
 *  Studio tier. Other levels are listed for completeness in case the
 *  curated list ever spans more than one tier (e.g. a "good enough"
 *  cheap Neural alongside Dragon HD). */
private val AZURE_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Studio,
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

private fun tierOrderFor(engine: VoiceEngine): List<QualityLevel> = when (engine) {
    VoiceEngine.Piper -> PIPER_TIER_ORDER
    VoiceEngine.Kokoro -> KOKORO_TIER_ORDER
    VoiceEngine.Azure -> AZURE_TIER_ORDER
}

/** Engine display order — Piper section first, Kokoro second, Azure
 *  third. Drives outer iteration order of [groupByEngineThenTier]. */
private val ENGINE_DISPLAY_ORDER: List<VoiceEngine> = listOf(
    VoiceEngine.Piper,
    VoiceEngine.Kokoro,
    VoiceEngine.Azure,
)

/** Group a list of voices first by [VoiceEngine] then by
 *  [QualityLevel], producing iteration-ordered nested maps the screen
 *  can render straight through. Outer order: Piper → Kokoro. Inner
 *  order: Low→Medium→High for Piper, Studio→High→Medium→Low for
 *  Kokoro (see [PIPER_TIER_ORDER] / [KOKORO_TIER_ORDER] for the why).
 *
 *  Empty engine buckets and empty tier buckets are dropped so the
 *  screen doesn't render hollow headers — a user with only Piper
 *  voices installed never sees a "Kokoro" sub-header, and vice versa.
 *  Within a tier the source list's order is preserved (the catalog
 *  curates a sensible default; re-sorting here would lose that). */
internal fun List<UiVoiceInfo>.groupByEngineThenTier(): Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> {
    if (isEmpty()) return emptyMap()
    val byEngine = groupBy { it.voiceEngine() }
    val out = linkedMapOf<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>>()
    for (engine in ENGINE_DISPLAY_ORDER) {
        val voicesInEngine = byEngine[engine]?.takeIf { it.isNotEmpty() } ?: continue
        val byTier = voicesInEngine.groupBy { it.qualityLevel }
        val tierMap = linkedMapOf<QualityLevel, List<UiVoiceInfo>>()
        for (tier in tierOrderFor(engine)) {
            byTier[tier]?.takeIf { it.isNotEmpty() }?.let { tierMap[tier] = it }
        }
        if (tierMap.isNotEmpty()) out[engine] = tierMap
    }
    return out
}
