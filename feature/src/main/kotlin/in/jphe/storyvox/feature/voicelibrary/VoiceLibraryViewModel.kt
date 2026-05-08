package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceFavorites
import `in`.jphe.storyvox.playback.voice.VoiceManager
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class VoiceLibraryUiState(
    /** Hand-picked best-of-catalog voices, shown above all other sections.
     *  Always 3 entries (sourced from [VoiceCatalog.featuredIds]); each
     *  carries its own installed flag so the row reflects current state. */
    val featured: List<UiVoiceInfo> = emptyList(),
    /** User-favourited voices, surfaced under their own header above
     *  Featured. Empty when nothing pinned, in which case the whole
     *  section is hidden by the screen. */
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
) : ViewModel() {

    private val _currentDownload = MutableStateFlow<DownloadingVoice?>(null)
    private val _pendingDelete = MutableStateFlow<UiVoiceInfo?>(null)
    private val _error = MutableStateFlow<String?>(null)

    private var downloadJob: Job? = null

    val uiState: StateFlow<VoiceLibraryUiState> = combine(
        voiceManager.installedVoices,
        flowOf(voiceManager.availableVoices),
        voiceManager.activeVoice,
        voiceFavorites.favoriteIds,
        combine(
            _currentDownload.asStateFlow(),
            _pendingDelete.asStateFlow(),
            _error.asStateFlow(),
        ) { d, p, e -> Triple(d, p, e) },
    ) { installed, available, active, favIds, (downloading, pending, error) ->
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        // Featured voices use the live installed flag so the row's CTA
        // ("Activate" vs "Download") matches reality. We pull the entry from
        // the installed list when present, otherwise from the catalog.
        val featured = VoiceCatalog.featuredIds.mapNotNull { id ->
            installed.firstOrNull { it.id == id }
                ?: available.firstOrNull { it.id == id }
        }
        val featuredIdSet = featured.mapTo(mutableSetOf()) { it.id }
        // Favourites pulls from installed first (preserving the
        // installed-flag) and falls back to the catalog for voices the
        // user pinned but hasn't downloaded yet. Listed favourites are
        // EXCLUDED from the per-tier sections below to avoid showing the
        // same row twice — featured rows follow the same exclusion rule.
        val favorites = favIds.mapNotNull { id ->
            installed.firstOrNull { it.id == id }
                ?: available.firstOrNull { it.id == id }
        }
        val excludedFromTiers = favIds + featuredIdSet
        val installedFiltered = installed.filterNot { it.id in excludedFromTiers }
        val availableFiltered = available.filterNot {
            it.id in installedIds || it.id in excludedFromTiers
        }
        VoiceLibraryUiState(
            featured = featured,
            favorites = favorites,
            installedByEngine = installedFiltered.groupByEngineThenTier(),
            availableByEngine = availableFiltered.groupByEngineThenTier(),
            favoriteIds = favIds,
            activeVoiceId = active?.id,
            currentDownload = downloading,
            pendingDelete = pending,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceLibraryUiState())

    fun toggleFavorite(voiceId: String) {
        viewModelScope.launch { voiceFavorites.toggle(voiceId) }
    }

    fun onRowTapped(voice: UiVoiceInfo) {
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
 *  — Piper section first, then Kokoro. */
enum class VoiceEngine { Piper, Kokoro }

private fun UiVoiceInfo.voiceEngine(): VoiceEngine = when (engineType) {
    is EngineType.Piper -> VoiceEngine.Piper
    is EngineType.Kokoro -> VoiceEngine.Kokoro
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

private fun tierOrderFor(engine: VoiceEngine): List<QualityLevel> = when (engine) {
    VoiceEngine.Piper -> PIPER_TIER_ORDER
    VoiceEngine.Kokoro -> KOKORO_TIER_ORDER
}

/** Engine display order — Piper section first, Kokoro second. Drives
 *  outer iteration order of [groupByEngineThenTier]. */
private val ENGINE_DISPLAY_ORDER: List<VoiceEngine> = listOf(
    VoiceEngine.Piper,
    VoiceEngine.Kokoro,
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
