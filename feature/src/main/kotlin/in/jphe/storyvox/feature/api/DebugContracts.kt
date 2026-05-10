package `in`.jphe.storyvox.feature.api

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/**
 * Read-only diagnostic surface for storyvox's debug overlay + Debug
 * screen (`/debug`). Distinct from [SettingsRepositoryUi] so the test
 * fakes can stay focused on settings without dragging a debug snapshot
 * through every stub, and so the orchestrating app/di module can build
 * the snapshot from many small sources without those sources knowing
 * about the UI shape.
 *
 * **Surface invariants**
 *  - `snapshot` MUST emit at most ~1 Hz. Listeners (overlay, screen) draw
 *    every emission; collecting at audio-frame cadence (44.1 kHz) would
 *    melt the UI thread and serve no user purpose since the overlay's
 *    smallest useful unit is "how does it look right now."
 *  - Read-only. No writers; the overlay is purely a window on engine state.
 *    Persistence (showOverlay toggle) lives in [SettingsRepositoryUi] —
 *    it's a user UX preference, not a debug datum.
 *  - Snapshot must never carry secrets. Azure key, OAuth tokens, etc. are
 *    represented as flags (`isConfigured: Boolean`) or short
 *    user-controlled identifiers (region id), never raw key material.
 *    A user pulling logs / screenshots for a bug report must be able to
 *    share what they see without leaking creds.
 *
 * Vesper, v0.4.97 — debut surface for the Debug overlay/screen.
 */
interface DebugRepositoryUi {
    /**
     * Hot flow of the latest snapshot. App impl combines flows from the
     * playback controller, Azure engine, network state, etc. Re-throttled
     * at ~1 Hz at the impl seam so transient bursts (rapid sentence
     * boundaries, retry storms) don't flood subscribers.
     */
    val snapshot: Flow<DebugSnapshot>

    /**
     * Build a single one-shot snapshot suitable for "copy to clipboard"
     * export. Distinct from [snapshot] because the export path wants the
     * current value once, with no subscriber lifecycle.
     */
    suspend fun captureSnapshot(): DebugSnapshot
}

/**
 * Frozen state of the audio + engine pipeline at one moment in time.
 *
 * Composition is intentionally flat per-section (no deep nesting) so the
 * overlay can render compact rows ("4 sentences in flight | 740ms buffer
 * | 22 lookahead chunks") without unwrapping nested optionals.
 *
 * All nullable fields read as "not applicable / not yet known"; the UI
 * shows an em-dash. Fields with a sensible zero (`audioBufferMs = 0`)
 * use that instead of `null` so we can chart them.
 */
@Immutable
data class DebugSnapshot(
    val playback: DebugPlayback,
    val engine: DebugEngine,
    val audio: DebugAudio,
    val azure: DebugAzure,
    val network: DebugNetwork,
    val storage: DebugStorage,
    val build: DebugBuildInfo,
    /**
     * Newest first. Implementations hold a ring of size 20; older events
     * fall off. The overlay shows the top 5; the screen shows all 20.
     */
    val events: List<DebugEvent> = emptyList(),
    /** Engineer's clock at snapshot time, ms since epoch. */
    val snapshotAtMs: Long,
) {
    companion object {
        /** Empty initial snapshot — used until the first real emission lands. */
        val EMPTY = DebugSnapshot(
            playback = DebugPlayback(),
            engine = DebugEngine(),
            audio = DebugAudio(),
            azure = DebugAzure(),
            network = DebugNetwork(),
            storage = DebugStorage(),
            build = DebugBuildInfo(),
            snapshotAtMs = 0L,
        )
    }
}

@Immutable
data class DebugPlayback(
    val pipelineRunning: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isWarmingUp: Boolean = false,
    val currentSentenceIndex: Int = -1,
    val totalSentences: Int = 0,
    val currentChapterId: String? = null,
    val currentFictionId: String? = null,
    val chapterTitle: String = "",
    val charOffset: Int = 0,
    /** Last [PlaybackError] subclass name; null when error-free. */
    val lastErrorTag: String? = null,
)

@Immutable
data class DebugEngine(
    /** "VoxSherpa / Piper", "VoxSherpa / Kokoro", "Azure", "Android TTS". */
    val displayName: String = "—",
    val voiceId: String? = null,
    val voiceLabel: String = "",
    /** Tier descriptor: "Tier 1 (serial)" / "Tier 2 (streaming)" /
     *  "Tier 3 (4× parallel)". Pre-load reads as "—". */
    val tier: String = "—",
    /** Parallel-synth: how many engine instances are loaded (primary + secondaries). */
    val parallelInstances: Int = 1,
    /** Threads per instance (0 = Auto from sherpa-onnx heuristic). */
    val threadsPerInstance: Int = 0,
    /** Speed × pitch applied to the current pipeline. */
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Inter-sentence pause multiplier (issue #90/#109). */
    val punctuationPauseMultiplier: Float = 1f,
)

@Immutable
data class DebugAudio(
    /** Producer-side queue occupancy (chunks waiting to be played). */
    val producerQueueDepth: Int = 0,
    /** Configured queue capacity ([UiSettings.playbackBufferChunks]). */
    val producerQueueCapacity: Int = 0,
    /** Estimated audio buffered behind the playhead, in ms. */
    val audioBufferMs: Long = 0L,
    /** Tier 3 — reorder buffer occupancy when parallel synth is on. */
    val reorderBufferOccupancy: Int = 0,
    /** AudioTrack sample rate (Hz). */
    val sampleRate: Int = 0,
    /** Current routed output device label ("Speaker", "Wired headphones",
     *  "BT — Pixel Buds Pro"). Empty when no AudioTrack yet. */
    val outputDevice: String = "",
)

@Immutable
data class DebugAzure(
    val isConfigured: Boolean = false,
    /** Region id only ("eastus") — never the key. */
    val regionId: String = "",
    /** Request queue depth on AzureSpeechClient (best-effort, may be 0
     *  if Azure isn't the active engine). */
    val pendingRequests: Int = 0,
    /** Last successful synthesize round-trip in ms. */
    val lastLatencyMs: Long? = null,
    /** Last [AzureError] subclass name; null when error-free. */
    val lastErrorTag: String? = null,
    /** Age in seconds of the cached `voices/list` payload. */
    val voiceCacheAgeSec: Long? = null,
)

@Immutable
data class DebugNetwork(
    val online: Boolean = true,
    /** ms since last successful chapter fetch (null = never). */
    val lastChapterFetchAgeMs: Long? = null,
    /** Last fetch-failed reason for the most recent attempted chapter. */
    val lastFetchError: String? = null,
)

@Immutable
data class DebugStorage(
    /** Count of chapters in the local Room cache. */
    val cachedChapters: Int = 0,
    /** Approximate bytes in chapter cache (sum of plainBody lengths × 2). */
    val cachedChapterBytes: Long = 0L,
    /** Bytes occupied by installed voice models. */
    val voiceModelBytes: Long = 0L,
)

@Immutable
data class DebugBuildInfo(
    val versionName: String = "",
    val hash: String = "",
    val branch: String = "",
    val dirty: Boolean = false,
    val built: String = "",
    val sigilName: String = "",
)

/**
 * One entry in the rolling event ring. The overlay surfaces a fixed
 * 20-event window — *not* a scrolling console (deliberately kept short
 * for readability per Vesper's design brief).
 */
@Immutable
data class DebugEvent(
    val timestampMs: Long,
    val kind: DebugEventKind,
    /** One-line human-readable summary. Examples:
     *  - "Chapter loaded: 12 — The Bonewright"
     *  - "Engine switched: Piper → Azure"
     *  - "Sentence 47 emitted (340 ms latency)"
     *  - "Auto-advance fired: chapter 13"
     *  - "Azure: 429 throttled, backing off 250ms"
     */
    val message: String,
)

enum class DebugEventKind {
    /** Chapter download / chapter swap. */
    Chapter,
    /** Engine swap / voice change. */
    Engine,
    /** Per-sentence boundary tick (mostly visible during Tier 3 debug). */
    Sentence,
    /** Errors of any flavor — emphasized in deep red in the UI. */
    Error,
    /** Pipeline lifecycle: start/stop/auto-advance/sleep-timer. */
    Pipeline,
    /** Generic info (network online/offline transitions, etc). */
    Info,
}
