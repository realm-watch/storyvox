package `in`.jphe.storyvox.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import `in`.jphe.storyvox.feature.api.DebugAudio
import `in`.jphe.storyvox.feature.api.DebugAzure
import `in`.jphe.storyvox.feature.api.DebugBuildInfo
import `in`.jphe.storyvox.feature.api.DebugEngine
import `in`.jphe.storyvox.feature.api.DebugEvent
import `in`.jphe.storyvox.feature.api.DebugEventKind
import `in`.jphe.storyvox.feature.api.DebugNetwork
import `in`.jphe.storyvox.feature.api.DebugPlayback
import `in`.jphe.storyvox.feature.api.DebugRepositoryUi
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import `in`.jphe.storyvox.feature.api.DebugStorage
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.source.azure.AzureCredentials
import `in`.jphe.storyvox.source.azure.AzureError
import `in`.jphe.storyvox.source.azure.AzureVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Production [DebugRepositoryUi]. Joins live state from
 * [PlaybackController], the Azure singleton, and a small in-process
 * event ring driven by [PlaybackController.events].
 *
 * Throttled at 1 Hz on the seam ([kotlinx.coroutines.flow.sample]) so
 * the overlay redraws at a deliberate cadence rather than at
 * sentence-boundary frequency (which could fire 5+ times per second on
 * short sentences and trash both battery and frame budget). This is
 * Vesper's design call from the mission brief: "Numeric values update
 * at 1Hz (not 60Hz — that's wasteful)".
 *
 * `sample` is kotlinx-coroutines preview API today. Its semantics
 * ("emit the most recent value once per interval, drop intermediates")
 * are stable across versions even if the API moves out of
 * `@FlowPreview`; the OptIn is a defensive marker, not a "this might
 * change behavior" signal.
 *
 * Never persists — the overlay is read-only. The single mutating
 * surface (the master switch) lives in
 * [SettingsRepositoryUi.setShowDebugOverlay]; this class just *reads*.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal class RealDebugRepositoryUi(
    private val context: Context,
    private val controller: PlaybackController,
    private val settings: SettingsRepositoryUi,
    private val azureCreds: AzureCredentials,
    private val azureEngine: AzureVoiceEngine,
) : DebugRepositoryUi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Rolling 20-event ring. Newest first per the [DebugSnapshot] contract.
     * Wrapped as a StateFlow so the snapshot combiner picks up additions
     * without polling. The ring's read path is a copy, so subscribers
     * never see a torn list mid-update.
     */
    private val events = MutableStateFlow<List<DebugEvent>>(emptyList())

    /**
     * Last successful chapter fetch elapsedRealtime ms. Recorded on every
     * `chapterId` change in the controller state stream — close-enough
     * proxy for "the playback layer is making progress." Sticky `null`
     * until a chapter loads (fresh session, no chapter yet).
     */
    @Volatile private var lastChapterFetchElapsedMs: Long? = null

    /** Last engine display name, for engine-swap event emission. */
    @Volatile private var lastEngineName: String? = null
    /** Last chapter id, for chapter-loaded event emission. */
    @Volatile private var lastChapterId: String? = null
    /** Last error tag, for error-change event emission. */
    @Volatile private var lastErrorTag: String? = null

    init {
        // Drive the event ring from the playback controller's state +
        // event stream. The state stream gives us the implicit events
        // (chapter change, error appearing); the explicit event flow gives
        // us BookFinished / ChapterChanged / EngineMissing / AzureFellBack.
        scope.launch {
            controller.state.collect { s ->
                onStateChange(s)
            }
        }
        scope.launch {
            controller.events.collect { ev ->
                onControllerEvent(ev)
            }
        }
        scope.launch {
            azureEngine.lastError
                .map { mapAzureErrorTag(it) }
                .distinctUntilChanged()
                .collect { tag ->
                    if (tag != null) {
                        pushEvent(DebugEventKind.Error, "Azure: $tag")
                    }
                }
        }
    }

    private fun onStateChange(s: PlaybackState) {
        // Chapter change → emit a "chapter loaded" event + stamp the
        // fetch wall time. Skip the no-op same-chapter re-emissions.
        if (s.currentChapterId != null && s.currentChapterId != lastChapterId) {
            lastChapterId = s.currentChapterId
            lastChapterFetchElapsedMs = SystemClock.elapsedRealtime()
            val title = s.chapterTitle?.takeIf { it.isNotBlank() } ?: "untitled"
            pushEvent(DebugEventKind.Chapter, "Loaded: $title")
        }
        // Error appears/changes → emit error event.
        val tag = s.error?.let { mapPlaybackErrorTag(it) }
        if (tag != lastErrorTag) {
            lastErrorTag = tag
            if (tag != null) pushEvent(DebugEventKind.Error, "Playback: $tag")
        }
        // Voice id swap → engine event.
        val engine = displayEngineName(s.voiceId)
        if (lastEngineName != null && engine != lastEngineName) {
            pushEvent(DebugEventKind.Engine, "Engine → $engine")
        }
        lastEngineName = engine
    }

    private fun onControllerEvent(ev: PlaybackUiEvent) {
        when (ev) {
            PlaybackUiEvent.BookFinished ->
                pushEvent(DebugEventKind.Pipeline, "Book finished")
            is PlaybackUiEvent.ChapterChanged ->
                pushEvent(DebugEventKind.Chapter, "Auto-advance → ${ev.chapterId.takeLast(16)}")
            is PlaybackUiEvent.EngineMissing ->
                pushEvent(DebugEventKind.Error, "Engine missing")
            is PlaybackUiEvent.AzureFellBack ->
                pushEvent(DebugEventKind.Engine, "Azure → ${ev.fallbackVoiceLabel} (fallback)")
        }
    }

    private fun pushEvent(kind: DebugEventKind, message: String) {
        val entry = DebugEvent(
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            message = message,
        )
        events.value = (listOf(entry) + events.value).take(EVENT_RING_SIZE)
    }

    /**
     * 1 Hz combined snapshot. Resamples the controller state and settings
     * flows on a steady cadence; the overlay collector never has to
     * worry about over-frequent recompositions even when the pipeline
     * is firing sentence boundaries 5/sec.
     *
     * Implementation note: combine emits whenever any upstream emits.
     * We `sample(1000)` afterward so a flurry of upstream emissions in
     * the same second collapses to one downstream tick.
     */
    override val snapshot: Flow<DebugSnapshot> = combine(
        controller.state,
        settings.settings,
        events,
        azureEngine.lastError,
    ) { playback, ui, ring, azureErr ->
        buildSnapshot(playback, ui, ring, azureErr)
    }
        .sample(REFRESH_INTERVAL_MS)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override suspend fun captureSnapshot(): DebugSnapshot {
        // One-shot read of the current values. controller.state is a
        // StateFlow, so .value is the freshest. settings.settings is a
        // generic Flow per the interface; we pull the first emission —
        // fast in practice because DataStore caches in-memory.
        val playback = controller.state.value
        val ui = settings.settings.first()
        return buildSnapshot(playback, ui, events.value, azureEngine.lastError.value)
    }

    private fun buildSnapshot(
        playback: PlaybackState,
        ui: `in`.jphe.storyvox.feature.api.UiSettings,
        ring: List<DebugEvent>,
        azureErr: AzureError?,
    ): DebugSnapshot {
        val engineName = displayEngineName(playback.voiceId)
        val voiceLabel = playback.voiceId.orEmpty().substringAfterLast(":")
            .ifBlank { playback.voiceId.orEmpty() }
        return DebugSnapshot(
            playback = DebugPlayback(
                pipelineRunning = playback.isPlaying || playback.isBuffering,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering,
                isWarmingUp = playback.isPlaying && playback.currentSentenceRange == null,
                currentSentenceIndex = playback.currentSentenceRange?.sentenceIndex ?: -1,
                totalSentences = 0, // not surfaced from PlaybackState today; future enhancement
                currentChapterId = playback.currentChapterId,
                currentFictionId = playback.currentFictionId,
                chapterTitle = playback.chapterTitle.orEmpty(),
                charOffset = playback.charOffset,
                lastErrorTag = playback.error?.let { mapPlaybackErrorTag(it) },
            ),
            engine = DebugEngine(
                displayName = engineName,
                voiceId = playback.voiceId,
                voiceLabel = voiceLabel,
                tier = tierFor(engineName, ui),
                parallelInstances = ui.parallelSynthInstances,
                threadsPerInstance = ui.synthThreadsPerInstance,
                speed = playback.speed,
                pitch = playback.pitch,
                punctuationPauseMultiplier = ui.punctuationPauseMultiplier,
            ),
            audio = DebugAudio(
                producerQueueDepth = 0, // not exposed from EnginePlayer today
                producerQueueCapacity = ui.playbackBufferChunks,
                audioBufferMs = 0L, // see follow-up issue
                reorderBufferOccupancy = 0,
                sampleRate = 0,
                outputDevice = "",
            ),
            azure = DebugAzure(
                isConfigured = azureCreds.isConfigured,
                regionId = azureCreds.regionId(),
                pendingRequests = 0,
                lastLatencyMs = null,
                lastErrorTag = mapAzureErrorTag(azureErr),
                voiceCacheAgeSec = null,
            ),
            network = DebugNetwork(
                online = isOnline(),
                lastChapterFetchAgeMs = lastChapterFetchElapsedMs?.let {
                    SystemClock.elapsedRealtime() - it
                },
                lastFetchError = null,
            ),
            storage = DebugStorage(
                cachedChapters = 0,
                cachedChapterBytes = 0L,
                voiceModelBytes = 0L,
            ),
            build = DebugBuildInfo(
                versionName = ui.sigil.versionName,
                hash = ui.sigil.hash,
                branch = ui.sigil.branch,
                dirty = ui.sigil.dirty,
                built = ui.sigil.built,
                sigilName = ui.sigil.name,
            ),
            events = ring,
            snapshotAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Best-effort engine label from a voice id. Voice ids follow the
     * pattern `<engine-prefix>:<voice-name>` in storyvox — Piper voices
     * carry `piper:`, Kokoro `kokoro:`, Azure `azure:`, Android TTS
     * `android:`. Unknown prefixes pass through as the raw prefix label.
     */
    private fun displayEngineName(voiceId: String?): String {
        if (voiceId.isNullOrBlank()) return "—"
        return when {
            voiceId.startsWith("azure:") -> "Azure"
            voiceId.startsWith("kokoro:") -> "VoxSherpa · Kokoro"
            voiceId.startsWith("piper:") -> "VoxSherpa · Piper"
            voiceId.startsWith("android:") -> "Android TTS"
            else -> voiceId.substringBefore(":").ifBlank { "Unknown" }
        }
    }

    private fun tierFor(engineName: String, ui: `in`.jphe.storyvox.feature.api.UiSettings): String {
        if (engineName.startsWith("Azure")) return "Streaming (Azure)"
        val instances = ui.parallelSynthInstances
        return when {
            instances <= 1 -> "Tier 1 / 2 (serial / streaming)"
            else -> "Tier 3 ($instances× parallel)"
        }
    }

    private fun mapPlaybackErrorTag(e: PlaybackError): String = when (e) {
        is PlaybackError.EngineUnavailable -> "EngineUnavailable"
        is PlaybackError.ChapterFetchFailed -> "ChapterFetchFailed"
        is PlaybackError.TtsSpeakFailed -> "TtsSpeakFailed"
        is PlaybackError.AzureAuthFailed -> "AzureAuthFailed"
        is PlaybackError.AzureThrottled -> "AzureThrottled"
        is PlaybackError.AzureNetworkUnavailable -> "AzureNetworkUnavailable"
        is PlaybackError.AzureServerError -> "AzureServerError(${e.httpCode})"
    }

    private fun mapAzureErrorTag(e: AzureError?): String? = when (e) {
        null -> null
        is AzureError.AuthFailed -> "AuthFailed"
        is AzureError.Throttled -> "Throttled"
        is AzureError.NetworkError -> "NetworkError"
        is AzureError.ServerError -> "ServerError(${e.httpCode})"
        is AzureError.BadRequest -> "BadRequest(${e.httpCode})"
    }

    /**
     * Cheap one-shot connectivity check. Not Flow-bound — runs on each
     * snapshot tick (1 Hz). Cheaper than registering a NetworkCallback +
     * tearing it down on subscriber lifecycle, and the latency of a 1s
     * stale answer is well under what the overlay would care about.
     */
    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        /** Per the [DebugSnapshot] contract. */
        const val EVENT_RING_SIZE = 20

        /** 1 Hz — matches Vesper's mission brief: "Numeric values update at
         *  1Hz (not 60Hz — that's wasteful)". */
        const val REFRESH_INTERVAL_MS = 1_000L
    }
}
