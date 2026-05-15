package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import android.os.Process as AndroidProcess
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_STEADY
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_STEADY
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.VoiceTuningConfig
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.SPEED_BASELINE_CHARS_PER_SECOND
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.SleepTimer
import `in`.jphe.storyvox.playback.TtsVolumeRamp
import `in`.jphe.storyvox.playback.cache.EngineMutex
import `in`.jphe.storyvox.playback.cache.PcmAppender
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.cache.PrerenderTriggers
import `in`.jphe.storyvox.playback.tts.source.CacheFileSource
import `in`.jphe.storyvox.playback.tts.source.EngineStreamingSource
import `in`.jphe.storyvox.playback.tts.source.PcmSource
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-process Media3 [Player] that bypasses Android's [android.speech.tts.TextToSpeech]
 * framework. Talks directly to VoxSherpa's [VoiceEngine] (via the engine-lib AAR
 * pulled from JitPack) and manages its own [AudioTrack] with a fat ~2-second buffer.
 *
 * Why this exists: the framework path's `audioAvailable()` chunks land in an
 * AudioTrack whose buffer is sized by the framework — typically small enough
 * that on modest hardware (Galaxy Tab A7 Lite) Piper inference for the next
 * sentence can't finish before the current sentence's buffer drains, producing
 * an audible silence between every sentence. With our own AudioTrack we set
 * a buffer big enough that audio of N keeps playing while we generate N+1 on
 * a worker thread. Seek/pause/skip become AudioTrack operations.
 *
 * Producer/consumer model:
 *  - **Producer** ([generationJob]): walks the sentence list from
 *    [currentSentenceIndex], calls [VoiceEngine.generateAudioPCM] for each,
 *    pushes the resulting PCM byte[] onto [pcmQueue] together with the
 *    sentence range. Blocks on [java.util.concurrent.LinkedBlockingQueue.put]
 *    if the queue is full (back-pressure).
 *  - **Consumer** ([consumerThread]): pulls from [pcmQueue], writes PCM to
 *    the AudioTrack via [AudioTrack.write] (blocking write — returns when
 *    the AudioTrack accepts the bytes). Before writing, fires a
 *    `scope.launch` to Main that surfaces the new sentence range to the UI.
 *
 * Pause/seek tear down the AudioTrack and re-create on resume; cheaper than
 * trying to keep state coherent across a `pause()` call.
 */
/**
 * Issue #189 — playback state for the one-shot recap-aloud TTS pipeline.
 * Distinct from [PlaybackState] because the recap is a transient utterance
 * with its own AudioTrack; conflating the two would force every chapter
 * playback observer to also reason about recap state.
 */
enum class RecapPlaybackState { Idle, Speaking }

@UnstableApi
class EnginePlayer @AssistedInject constructor(
    @Assisted private val context: Context,
    private val chunker: SentenceChunker,
    private val chapterRepo: ChapterRepository,
    private val positionRepo: PlaybackPositionRepository,
    /**
     * Issue #158 — reading-history breadcrumb. Written on every
     * chapter-load inside [loadAndPlay] and stamped `completed = true`
     * inside [handleChapterDone]. Forever retention; powers the
     * Library "History" sub-tab.
     */
    private val historyRepo: HistoryRepository,
    private val sleepTimer: SleepTimer,
    private val voiceManager: VoiceManager,
    private val volumeRamp: TtsVolumeRamp,
    private val bufferConfig: PlaybackBufferConfig,
    private val modeConfig: PlaybackModeConfig,
    private val voiceTuningConfig: VoiceTuningConfig,
    private val pronunciationDictRepo: PronunciationDictRepository,
    /** PR-4 (#183) — Azure HD voices BYOK plumbing. The credentials
     *  store is the gate (no key → can't activate); the engine adapter
     *  satisfies the same `VoiceEngineHandle` contract Piper/Kokoro
     *  use, with HTTPS round-trips replacing JNI calls. */
    private val azureCredentials: `in`.jphe.storyvox.source.azure.AzureCredentials,
    private val azureVoiceEngine: `in`.jphe.storyvox.source.azure.AzureVoiceEngine,
    /** PR-6 (#185) — Azure offline-fallback. Read at error-time inside
     *  observeAzureErrors; observed as a flow to keep the snapshot
     *  fresh without forcing every error-path to suspend. */
    private val azureFallbackConfig: `in`.jphe.storyvox.data.repository.playback.AzureFallbackConfig,
    /** Tier 3 (#88) — experimental parallel-synth toggle. Snapshotted
     *  at pipeline-construction time inside loadAndPlay/startPlayback
     *  so a mid-chapter flip doesn't half-construct a second engine
     *  with no cleanup; takes effect on next pipeline rebuild. */
    private val parallelSynthConfig: `in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig,
    /** Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — extra
     *  inter-sentence silence applied when TalkBack is active. The
     *  flow is already gated by `isTalkBackActive`; outside TalkBack
     *  it emits 0 and the producer's existing punctuation-pause path
     *  keeps the audiobook-tuned default. */
    private val a11yPacingConfig: `in`.jphe.storyvox.data.repository.playback.A11yPacingConfig,
    /** PR-D (#86) — on-disk PCM cache. EnginePlayer is the only thing
     *  that knows the (chapter, voice, speed, pitch, dict) identity at
     *  pipeline-construction time, so it owns key construction here.
     *  The cache itself is a `@Singleton` injected by Hilt. */
    private val pcmCache: PcmCache,
    /** PR-F (#86) — process-wide engine mutex hoisted to a `@Singleton`
     *  so the background [`in`.jphe.storyvox.playback.cache.ChapterRenderJob]
     *  worker takes the SAME instance the foreground player uses.
     *  Without sharing, a worker render could call `generateAudioPCM`
     *  concurrent with `loadAndPlay`'s `loadModel` — the issue #11
     *  SIGSEGV race. */
    private val engineMutexHolder: EngineMutex,
    /** PR-F (#86) — chapter-natural-end trigger source. The streaming
     *  pipeline's consumer thread calls [handleChapterDone] when the
     *  end-of-stream pill arrives; that path forwards to
     *  [PrerenderTriggers.onChapterCompleted] so the scheduler enqueues
     *  the N+2 render (N+1 was scheduled when N started or is already
     *  in flight). */
    private val prerenderTriggers: PrerenderTriggers,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    @AssistedFactory
    interface Factory {
        fun create(context: Context): EnginePlayer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var sentences: List<Sentence> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentPitch: Float = 1.0f

    /** Issue #90 — multiplier on the inter-sentence silence the producer
     *  splices after each sentence's PCM. 0f = no trailing silence at all,
     *  1f = the audiobook-tuned default, >1f lengthens. Wired through to
     *  [EngineStreamingSource] on every pipeline rebuild (same lifecycle
     *  as [currentSpeed] and [currentPitch] — changing it via
     *  [setPunctuationPauseMultiplier] rebuilds the pipeline if playing,
     *  so the new value takes effect on the next sentence boundary). */
    private var currentPunctuationPauseMultiplier: Float = 1f

    /** Engine type for the currently-loaded voice. Set in [loadAndPlay]
     *  after a successful model load; read by the producer in
     *  [startPlaybackPipeline] to decide which engine drives generation. */
    private var activeEngineType: EngineType? = null

    /** Voice id whose model is currently loaded. Used by the active-voice
     *  watcher (see [observeActiveVoice]) to decide whether a DataStore
     *  emission represents a real change worth reloading for, and by
     *  [resume] to detect a "voice changed while paused" situation that
     *  needs a full reload before playback can continue with the new model. */
    private var loadedVoiceId: String? = null

    /** Flagged when the user picks a different voice while playback is
     *  paused. The flag tells [resume] to route through [loadAndPlay] for
     *  a fresh model load instead of restarting the existing pipeline. */
    private var voiceReloadPending: Boolean = false

    /**
     * Live-cached pre-synth queue depth. Driven by [bufferConfig.playbackBufferChunks];
     * read by [startPlaybackPipeline] when constructing each new
     * [EngineStreamingSource]. The cache exists because pipeline construction is a
     * synchronous code path (called from [SimpleBasePlayer] handlers) and the
     * underlying DataStore flow is suspending. Volatile because the writer is the
     * collector coroutine and the readers are pipeline threads.
     */
    @Volatile
    private var cachedBufferChunks: Int = 8 // BUFFER_DEFAULT_CHUNKS — duplicated to avoid feature dep

    /** Snapshot of [ParallelSynthConfig.parallelSynthState.instances]
     *  read at sync construction sites (Tier 3 secondary handle
     *  building inside [startPlaybackPipeline]). Same volatile-cache
     *  pattern as [cachedBufferChunks] — the underlying flow is
     *  suspending, the consumer is sync. Defaults to 1 (serial). */
    @Volatile
    private var cachedParallelSynthInstances: Int = 1

    /** Tier 3 (#88) — list of secondary VoiceEngine / KokoroEngine
     *  instances for parallel synth. Sized at (instances-1) so the
     *  primary singleton + N-1 secondaries = N total engines.
     *  Owned by EnginePlayer so loadAndPlay's voice-swap can destroy
     *  them cleanly on engine-type change. Empty list = serial mode. */
    @Volatile
    private var secondaryPiperEngines: List<com.CodeBySonu.VoxSherpa.VoiceEngine> = emptyList()

    @Volatile
    private var secondaryKokoroEngines: List<com.CodeBySonu.VoxSherpa.KokoroEngine> = emptyList()

    /** Issue #119 — Kitten secondaries for the Tier 3 parallel-synth
     *  slider. Each loaded Kitten session is small (~60–80 MB resident
     *  on the fp16 nano model), so an 8-way fan-out fits comfortably
     *  even on a 2 GB device — Kitten is the friendliest engine for the
     *  parallel slider on low-end hardware. Owned per-engine-type like
     *  the other two; voice swap away from Kitten frees this list. */
    @Volatile
    private var secondaryKittenEngines: List<com.CodeBySonu.VoxSherpa.KittenEngine> = emptyList()

    /**
     * Issue #98 — Mode B (Catch-up Pause) live cache. Gates the consumer
     * thread's pause-buffer-resume branches in [startPlaybackPipeline].
     * Volatile because the writer is the collector coroutine and the reader
     * is the URGENT_AUDIO consumer thread; flips take effect on the next
     * iteration of the consumer loop with no pipeline rebuild.
     *
     * Default true preserves PR #77's pause-buffer-resume contract; reading
     * `true` until the first DataStore emission is the safe bias.
     */
    @Volatile
    private var cachedCatchupPause: Boolean = true

    /**
     * Issue #85 — Voice-Determinism preset live cache. Mirrors the
     * persisted DataStore Boolean (`true` = Steady, `false` = Expressive).
     * Default `true` matches the persisted-default + the calmed VITS
     * defaults VoxSherpa has shipped pre-#85, so a fresh install with no
     * DataStore emission yet hits the same noise_scale values the engine
     * defaults to (`VoiceEngine.DEFAULT_NOISE_SCALE` = 0.35).
     *
     * Applied to the live engine via [applyVoiceTuning] when the flow
     * emits, NOT in the consumer/producer hot paths. Volatile because
     * the writer is the collector coroutine on `scope` and the reader
     * is [applyVoiceTuning] (also on `scope`); the cache is purely a
     * "did this actually change?" gate so repeat emissions are cheap.
     */
    @Volatile
    private var cachedVoiceSteady: Boolean = true

    /**
     * Issue #135 — live cache of the user's pronunciation dictionary.
     * Read by [startPlaybackPipeline] when constructing each new
     * [EngineStreamingSource]; the lambda passed to the source closes
     * over the dict instance so substitution applies the most-recent
     * dict at pipeline-construction time. Mid-pipeline edits take
     * effect on the next pipeline rebuild (next chapter / seek / voice
     * swap / speed/pitch change), which matches every other DataStore-
     * driven knob in this class.
     *
     * Volatile because the writer is the collector coroutine and the
     * readers are pipeline construction (Main) + the producer worker.
     * Default is [PronunciationDict.EMPTY] — an identity substitution
     * — so the first pipeline before DataStore hydrates renders
     * exactly like pre-#135.
     */
    @Volatile
    private var cachedPronunciationDict: PronunciationDict = PronunciationDict.EMPTY

    /**
     * Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — current
     * extra inter-sentence silence in ms. Folds the TalkBack-active
     * signal with the user's slider; the [A11yPacingConfig] flow
     * emits 0 whenever TalkBack is off (so the producer's existing
     * punctuation-pause is the only gap during sighted playback).
     *
     * Volatile because the writer is the collector coroutine on
     * `scope` and the reader is [EngineStreamingSource] (producer
     * worker thread). Mid-pipeline flips take effect on the next
     * sentence boundary — same lifecycle as [currentPunctuationPauseMultiplier].
     */
    @Volatile
    private var cachedA11yExtraSilenceMs: Int = 0

    init {
        observeActiveVoice()
        observeBufferConfig()
        observeModeConfig()
        observeVoiceTuningConfig()
        observePronunciationDict()
        observeAzureErrors()
        observeAzureFallbackConfig()
        observeA11yPacing()
    }

    /**
     * #486 / #488 — keep [cachedA11yExtraSilenceMs] fresh so the
     * producer's per-sentence pause adds the TalkBack pad without
     * suspending. The flow only emits non-zero when TalkBack is
     * actually active; outside TalkBack we stay at 0 and the
     * existing punctuation-pause math is unchanged.
     */
    private fun observeA11yPacing() {
        scope.launch {
            a11yPacingConfig.extraSilenceMs.collect { ms ->
                cachedA11yExtraSilenceMs = ms.coerceIn(0, 1500)
            }
        }
    }

    /** PR-6 (#185) — keep [azureFallbackEnabled] / [azureFallbackVoiceId]
     *  fresh so observeAzureErrors can read them without awaiting a
     *  flow tick inside the error-path. */
    private fun observeAzureFallbackConfig() {
        scope.launch {
            azureFallbackConfig.state.collect { s ->
                azureFallbackEnabled = s.enabled
                azureFallbackVoiceId = s.fallbackVoiceId
            }
        }
    }

    /**
     * PR-5 (#184) — bridge [AzureVoiceEngine.lastError] to
     * [PlaybackState.error]. Each Azure error type maps to a distinct
     * [PlaybackError] subclass so the UI can render different copy
     * (auth-error → re-paste prompt; throttled → quota-hint; offline
     * → "network required"). Null clears the error.
     */
    private fun observeAzureErrors() {
        scope.launch {
            azureVoiceEngine.lastError.collect { err ->
                val mapped: PlaybackError? = when (err) {
                    null -> null
                    is `in`.jphe.storyvox.source.azure.AzureError.AuthFailed ->
                        PlaybackError.AzureAuthFailed
                    is `in`.jphe.storyvox.source.azure.AzureError.Throttled ->
                        PlaybackError.AzureThrottled(err.message ?: "Azure throttled.")
                    is `in`.jphe.storyvox.source.azure.AzureError.NetworkError ->
                        PlaybackError.AzureNetworkUnavailable(
                            err.message ?: "Network error reaching Azure.",
                        )
                    is `in`.jphe.storyvox.source.azure.AzureError.ServerError ->
                        PlaybackError.AzureServerError(
                            httpCode = err.httpCode,
                            message = err.message ?: "Azure server error.",
                        )
                    is `in`.jphe.storyvox.source.azure.AzureError.BadRequest ->
                        // Bad SSML is rare and usually engine-side; surface
                        // as a generic Azure server error so the user
                        // doesn't see an "azure rejected SSML" message
                        // they can't act on. Logs catch the detail.
                        PlaybackError.AzureServerError(
                            httpCode = err.httpCode,
                            message = err.message ?: "Azure rejected request.",
                        )
                }
                _observableState.update { it.copy(error = mapped) }

                // #251 (v0.4.88) — terminal errors must stop the pipeline.
                // Pre-fix: AuthFailed and BadRequest threw from
                // AzureVoiceEngine.synthesize, the producer's catch-all
                // swallowed them silently, and the consumer parked on
                // queue.take forever (no END_PILL pushed). For BadRequest
                // specifically, the OLD code returned null per sentence —
                // so the producer kept retrying ~30 req/s, the chapter
                // synthesized to entirely-empty, and the consumer's "all
                // sentences null" path was misread as "natural end of
                // chapter" → spam-advanced through chapters at ~1/sec
                // burning the user's Azure quota for zero audio.
                //
                // Fix: when the engine emits a terminal AzureError
                // (AuthFailed or BadRequest), stop the pipeline
                // explicitly. The fallback-swap path below still
                // handles non-terminal errors (Throttled, Network,
                // ServerError) the same way.
                val isTerminal = err is `in`.jphe.storyvox.source.azure.AzureError.AuthFailed ||
                    err is `in`.jphe.storyvox.source.azure.AzureError.BadRequest
                if (isTerminal && activeEngineType is EngineType.Azure) {
                    _observableState.update { it.copy(isPlaying = false) }
                    stopPlaybackPipeline()
                }

                // PR-6 (#185) — offline-fallback. Auth errors are NOT
                // fall-back-able (a bad key won't recover by switching
                // voice; the user has to re-paste). BadRequest also not
                // fall-back-able as of #251 — a different voice in the
                // same Azure region might 400 too, and the live-roster
                // pivot in v0.4.84 should prevent the wrong-voice-name
                // root cause anyway. Other errors fall-back-able if
                // the toggle is on AND a fallback voice id is set AND
                // we haven't already swapped this chapter.
                if (err != null &&
                    !isTerminal &&
                    activeEngineType is EngineType.Azure &&
                    azureFallbackEnabled &&
                    azureFallbackVoiceId != null &&
                    !azureFallbackEmittedThisChapter
                ) {
                    val fallbackId = azureFallbackVoiceId!!
                    val fallbackEntry = `in`.jphe.storyvox.playback.voice.VoiceCatalog.byId(fallbackId)
                    val label = fallbackEntry?.displayName ?: fallbackId
                    azureFallbackEmittedThisChapter = true
                    _uiEvents.tryEmit(PlaybackUiEvent.AzureFellBack(label))
                    scope.launch { voiceManager.setActive(fallbackId) }
                }
            }
        }
    }

    /** PR-6 (#185) — Azure offline-fallback config snapshot, refreshed
     *  whenever the settings flow ticks. Read at error-time inside
     *  [observeAzureErrors] (no flow-collect race because the snapshot
     *  is updated on the same scope). */
    @Volatile
    private var azureFallbackEnabled: Boolean = false

    @Volatile
    private var azureFallbackVoiceId: String? = null

    /** Per-chapter dedupe so the fallback toast doesn't fire on every
     *  failed sentence — once the swap has happened, subsequent Azure
     *  errors in the same chapter are silently ignored (the active
     *  voice is no longer Azure anyway, after the swap). Reset on
     *  chapter change. */
    @Volatile
    private var azureFallbackEmittedThisChapter: Boolean = false

    /** Internal hook for [observeFallbackConfig] to update the snapshot. */
    internal fun setAzureFallbackSnapshot(enabled: Boolean, voiceId: String?) {
        azureFallbackEnabled = enabled
        azureFallbackVoiceId = voiceId
    }

    private fun observeBufferConfig() {
        scope.launch {
            // Rebuild the pipeline whenever the buffer-chunks slider
            // changes mid-listen so the new queueCapacity takes effect
            // on the next sentence — without this the cache would
            // update but [EngineStreamingSource] would keep its old
            // capacity until the next chapter load. Same shape as
            // setPunctuationPauseMultiplier's mid-listen seam. The
            // initial hydration emission lands before isPlaying flips
            // true, so there's no spurious rebuild on launch.
            bufferConfig.playbackBufferChunks.collect { v ->
                if (cachedBufferChunks == v) return@collect
                cachedBufferChunks = v
                if (_observableState.value.isPlaying) startPlaybackPipeline()
            }
        }
        scope.launch {
            // Keep [cachedParallelSynthInstances] in sync with the
            // user's parallel-synth slider for the synchronous Azure
            // lookahead path inside [startPlaybackPipeline]. Piper /
            // Kokoro read parallelState directly from suspend
            // contexts; Azure can't because pipeline construction is
            // sync. Snapshot pattern matches [cachedBufferChunks].
            parallelSynthConfig.parallelSynthState.collect { state ->
                cachedParallelSynthInstances = state.instances.coerceIn(1, 8)
            }
        }
    }

    private fun observeModeConfig() {
        scope.launch {
            modeConfig.catchupPause.collect { v ->
                cachedCatchupPause = v
            }
        }
    }

    /** Issue #135 — collect dictionary edits into [cachedPronunciationDict]
     *  so the next pipeline construction picks up the latest entries.
     *  The collector is alive for the player's lifetime; cancellation
     *  follows [scope] when the player is torn down. */
    private fun observePronunciationDict() {
        scope.launch {
            pronunciationDictRepo.dict.collect { v ->
                cachedPronunciationDict = v
            }
        }
    }

    /**
     * Issue #85 — Watches the persisted Voice-Determinism preset and
     * applies it to the live VoxSherpa engine on every change.
     *
     * Each emission lands on the Main dispatcher (via `scope`'s default),
     * but the actual setter call hops to [Dispatchers.IO] because
     * `VoiceEngine.setNoiseScale*()` may destroy + reconstruct
     * `OfflineTts` (a JNI sherpa-onnx call that takes ~1-3 s on Piper).
     * We hold [engineMutex] during that work so it serializes against
     * any in-flight `generateAudioPCM` in the producer — without it the
     * producer's JNI generate could be running while we free `tts`,
     * producing a SIGSEGV (the same hazard `loadModel` already handles
     * by holding [engineMutex] in [loadAndPlay]).
     *
     * If no model is loaded yet, the setters still apply: VoxSherpa
     * stores the values internally and applies them on the next
     * `loadModel()`.
     *
     * Kokoro models ignore noise_scale (they're not VITS). The setters
     * are no-ops on the Kokoro engine, but we still call them through
     * `VoiceEngine` because `VoiceEngine` holds the cached config that
     * applies to the next Piper voice the user picks.
     */
    private fun observeVoiceTuningConfig() {
        scope.launch {
            voiceTuningConfig.voiceSteady.collect { steady ->
                if (cachedVoiceSteady == steady) return@collect
                cachedVoiceSteady = steady
                applyVoiceTuning(steady)
            }
        }
    }

    /**
     * Hops to IO + holds [engineMutex] while pushing the (noise_scale,
     * noise_scale_w) preset down to VoxSherpa's `VoiceEngine`. The setter
     * itself decides whether a model reload is needed (no-op when the new
     * value matches the active value, full destroy + reconstruct
     * otherwise).
     *
     * The first emission on a fresh install carries the default `true`
     * (Steady) which matches `VoiceEngine.DEFAULT_NOISE_SCALE` already —
     * `cachedVoiceSteady` defaults to `true` and [observeVoiceTuningConfig]
     * gates on inequality, so we never trigger a redundant first-pass
     * reload.
     */
    private suspend fun applyVoiceTuning(steady: Boolean) {
        val noiseScale = if (steady) NOISE_SCALE_STEADY else NOISE_SCALE_EXPRESSIVE
        val noiseScaleW = if (steady) NOISE_SCALE_W_STEADY else NOISE_SCALE_W_EXPRESSIVE
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                // VoiceEngine.setNoiseScale* are synchronized internally and
                // each will reload the active model exactly once if the value
                // changed. Calling both in sequence under engineMutex means
                // worst-case we trigger two reloads — Piper goes 0.35→0.667
                // then 0.667→0.8 — in practice still ≤6 s of warm-up. Could
                // be batched into a single OfflineTts swap upstream later, but
                // not worth the API surface in v1.
                VoiceEngine.getInstance().setNoiseScale(noiseScale)
                VoiceEngine.getInstance().setNoiseScaleW(noiseScaleW)
            }
        }
    }

    /**
     * Watches [VoiceManager.activeVoice] and reacts to user-driven voice
     * changes. The catalog of cases:
     *
     *  - Active voice changes mid-playback → reload the engine and resume
     *    from the current char offset so the listener hears the new voice
     *    immediately (issue #8).
     *  - Active voice changes while paused → flag a pending reload and stop
     *    the existing pipeline; the next [resume] call will do a full
     *    [loadAndPlay] with the new voice.
     *  - Active voice changes before any chapter is loaded → no-op; the
     *    next [loadAndPlay] reads activeVoice itself.
     *
     *  De-dup: we track [loadedVoiceId] so re-emissions of the same id
     *  (e.g. after [setActive] writes the same value, or first-launch
     *  hydration) are filtered out.
     */
    private fun observeActiveVoice() {
        scope.launch {
            voiceManager.activeVoice.collect { active ->
                val newId = active?.id ?: return@collect
                if (newId == loadedVoiceId) {
                    // No-op flip — typically the user re-activated the same
                    // voice, or DataStore re-emitted the persisted value. If
                    // a previous flip had armed [voiceReloadPending] for a
                    // different id and the user has now flipped *back* to
                    // the loaded voice before resuming, clear the pending
                    // flag so we don't force a needless model reload (a
                    // 30 s Kokoro warm-up) on the next [resume].
                    voiceReloadPending = false
                    return@collect
                }
                val s = _observableState.value
                val fictionId = s.currentFictionId
                val chapterId = s.currentChapterId
                if (fictionId == null || chapterId == null) return@collect
                if (s.isPlaying) {
                    // Live swap. Tear down the current pipeline FIRST so the
                    // old generator can't keep pushing old-voice PCM into
                    // the (about-to-be-replaced) queue while loadAndPlay
                    // sits in loadModel for ~30 s on Kokoro. Without this,
                    // the user hears 5–10 s of stale audio before silence
                    // and finally the new voice.
                    stopPlaybackPipeline()
                    loadAndPlay(fictionId, chapterId, s.charOffset)
                } else {
                    voiceReloadPending = true
                    stopPlaybackPipeline()
                    activeEngineType = null
                }
            }
        }
    }

    /** AudioTrack for the active chapter. Recreated on play/seek/sample-rate change. */
    private var audioTrack: AudioTrack? = null

    /** PCM source feeding the consumer thread. Currently always the streaming
     *  engine impl; PR-E adds a CacheFileSource branch when a complete cache
     *  file exists for `(chapterId, voiceId, speed, pitch, chunkerVersion)`. */
    private var pcmSource: PcmSource? = null

    /**
     * Issue #290 — running tally of audio frames written to the main
     * AudioTrack since the last pipeline build. One frame = one PCM
     * sample on the single mono channel; at 24 kHz that's 1/24000 s
     * per frame. The Debug overlay's `audio buffered` row computes
     * `(framesWritten - track.playbackHeadPosition) * 1000 / sampleRate`
     * to express buffered ms.
     *
     * Updated from the consumer thread inside the write loop. Volatile
     * read on the snapshot path; the consumer is single-threaded so no
     * atomic increment is needed. Reset to 0 on every new pipeline
     * build so the delta against playbackHeadPosition stays meaningful.
     */
    @Volatile private var totalFramesWritten: Long = 0L

    /** Inter-chunk gap measurement (Tab A7 Lite TTS perf lane). Off by
     *  default — reads a marker file at every chunkStart so a developer
     *  can `adb shell touch /data/data/in.jphe.storyvox/files/chunk-gap-log`
     *  and start collecting numbers without a build flip. See [ChunkGapLogger]. */
    private val chunkGapLogger = ChunkGapLogger(context)

    /** Dedicated playback thread. The agent that gets URGENT_AUDIO and never
     *  yields back to a coroutine pool — see the comment on [startPlaybackPipeline]
     *  for why this can't be a coroutine. */
    private var consumerThread: Thread? = null

    /** Per-pipeline run flag. Flipped to false by [stopPlaybackPipeline]; the
     *  consumer checks it inside both the inter-sentence and intra-sentence
     *  loops so it can bail out of long [AudioTrack.write] sequences without
     *  waiting for the buffer to drain. */
    private val pipelineRunning = AtomicBoolean(false)

    /** Serializes [VoiceEngine.generateAudioPCM] / [KokoroEngine.generateAudioPCM]
     *  calls. The VoxSherpa engines are process-singletons and the underlying
     *  Sonic/onnxruntime state isn't safe across concurrent threads. Every
     *  pipeline-restart event ([setSpeed], [setPitch], [seekToCharOffset],
     *  voice swap) cancels the old generator coroutine, but cancellation only
     *  fires at suspension points — a JNI call already in flight runs to
     *  completion. Without this mutex, the new pipeline's generator can call
     *  the engine *while the old one is still inside it*, corrupting the
     *  internal state and producing garbled PCM.
     *
     *  PR-F (#86) — was a private `Mutex()` here pre-PR-F; hoisted to a
     *  Hilt `@Singleton` so the background [`in`.jphe.storyvox.playback.cache.ChapterRenderJob]
     *  shares the same instance. Implementation is unchanged — the same
     *  `kotlinx.coroutines.sync.Mutex`, same `withLock` callsites — just
     *  read through the holder so production + background workers see
     *  the same lock. */
    private val engineMutex: Mutex get() = engineMutexHolder.mutex

    private val _observableState = MutableStateFlow(PlaybackState())
    val observableState: StateFlow<PlaybackState> = _observableState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<PlaybackUiEvent> = _uiEvents.asSharedFlow()

    /** Issue #189 — one-shot recap-aloud TTS pipeline state. Distinct from
     *  the chapter pipeline because the recap is a transient utterance, not
     *  a chapter: it doesn't update charOffset, doesn't bind to a fiction
     *  id, doesn't persist a position. The two pipelines share the engine
     *  (via [engineMutex]) but have independent AudioTrack + consumer-thread
     *  state. The chapter pipeline must be paused before [speak] starts —
     *  the caller does that, this state is purely for the recap UI's
     *  play/pause toggle. */
    private val _recapPlayback = MutableStateFlow(RecapPlaybackState.Idle)
    val recapPlayback: StateFlow<RecapPlaybackState> = _recapPlayback.asStateFlow()

    /** Recap-only AudioTrack. Lives independently from [audioTrack] so a
     *  recap doesn't tear down chapter playback state. Released by the
     *  recap consumer thread on its own finally block, matching the
     *  chapter-pipeline shape (release-from-writer-thread, no JNI race). */
    private var recapAudioTrack: AudioTrack? = null
    private var recapPcmSource: PcmSource? = null
    private var recapConsumerThread: Thread? = null
    private val recapPipelineRunning = AtomicBoolean(false)

    override fun getState(): State {
        val s = _observableState.value
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                    )
                    .build(),
            )
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (s.currentChapterId != null) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaylist(buildPlaylist(s))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs { (s.charOffset * 1000L) / charsPerSecondLong() }
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // CONTENT_TYPE_MUSIC, not _SPEECH — see createAudioTrack().
                    // Mirrors the AudioTrack-level fix; otherwise the
                    // MediaSession descriptor still advertises speech to
                    // AudioFlinger and Samsung's session-metadata routing
                    // can apply speech-DSP independently of the AudioTrack.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setPlaybackParameters(PlaybackParameters(currentSpeed, currentPitch))
            .build()
    }

    private fun buildPlaylist(s: PlaybackState): ImmutableList<MediaItemData> {
        val chapterId = s.currentChapterId ?: return ImmutableList.of()
        return ImmutableList.of(
            MediaItemData.Builder(chapterId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.chapterTitle ?: "Chapter")
                        .setAlbumTitle(s.bookTitle)
                        .setArtworkUri(s.coverUri?.let { android.net.Uri.parse(it) })
                        .build(),
                )
                .setDurationUs(s.durationEstimateMs * 1000L)
                .build(),
        )
    }

    private fun charsPerSecondLong(): Long {
        val v = SPEED_BASELINE_CHARS_PER_SECOND * currentSpeed
        return v.toLong().coerceAtLeast(1L)
    }

    // ----- Storyvox-internal API -----

    suspend fun loadAndPlay(fictionId: String, chapterId: String, charOffset: Int) {
        // PR-6 (#185) — fallback toast dedupe is per-chapter; reset
        // here so the next chapter's first Azure failure can re-fire
        // the toast. Cheap (one volatile write) so we don't gate it
        // on whether the chapter actually changed.
        azureFallbackEmittedThisChapter = false
        val chapter: PlaybackChapter = chapterRepo.getChapter(chapterId) ?: run {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed(
                        "Chapter not ready (id=$chapterId). Download may still be queued.",
                    ),
                )
            }
            return
        }

        // Issue #373 — audio-stream backend (KVMR community radio + future
        // LibriVox / Internet Archive). When the chapter carries a
        // Media3-routable URL, fork off to the ExoPlayer-backed code path
        // and bypass TTS entirely. The TTS pipeline below assumes a voice
        // model + text body; neither applies to a live stream.
        if (chapter.audioUrl != null) {
            loadAndPlayAudioStream(fictionId, chapterId, chapter)
            return
        }

        // Issue #373 — coming back to a text chapter from an audio
        // chapter (KVMR → Royal Road) needs to tear down the sibling
        // ExoPlayer so it doesn't keep streaming under the TTS
        // playback. The clear-isLiveAudioChapter flag re-enables the
        // pitch slider UI in the same emit.
        if (_observableState.value.isLiveAudioChapter) {
            stopAudioStreamPlayer()
            _observableState.update { it.copy(isLiveAudioChapter = false) }
        }

        val active = voiceManager.activeVoice.first()
        if (active == null) {
            _observableState.update {
                it.copy(isPlaying = false, error = PlaybackError.EngineUnavailable)
            }
            return
        }

        // Surface the chapter + isPlaying=true BEFORE the model loads so the
        // UI's "warming up" state (sentenceEnd == 0 && isPlaying) shows the
        // brass spinner immediately. Sherpa-onnx Kokoro init can take 30+s
        // on modest hardware; without this the screen sits blank that long.
        val text = chapter.text
        sentences = chunker.chunk(text)
        // Issue #442 — Gutenberg-derived plain text can be "stripTags(htmlBody)"-
        // empty for spine entries that are pure-HTML wrappers (front-matter,
        // PG header pages, image-only inserts). When that happens the
        // chapter row carries non-empty text from getChapter()'s
        // is-not-empty guard (so we got here) but the sentence chunker
        // emits zero sentences — the producer loop then iterates 0
        // entries, pushes END_PILL immediately, the consumer treats
        // that as naturalEnd, and the user sees state=PLAYING +
        // position=0 forever because `isPlaying=true` was already
        // surfaced above. Surface a typed error and bail before the
        // pipeline spins up so the UI can render "Couldn't read this
        // chapter aloud" rather than buffering indefinitely. The
        // brass spinner clears on isPlaying=false; the error renders
        // in the player's error band.
        if (sentences.isEmpty()) {
            android.util.Log.w(
                "EnginePlayer",
                "loadAndPlay: chapter $chapterId yielded zero sentences " +
                    "(text.length=${text.length}, first 80=${text.take(80)}); " +
                    "surfacing typed error — see #442",
            )
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed(
                        "This chapter has no readable text — try the next chapter.",
                    ),
                )
            }
            invalidateState()
            return
        }
        currentSentenceIndex = sentences.indexOfFirst { charOffset <= it.endChar }
            .takeIf { it >= 0 } ?: 0
        // Issue #442 — synth event log on the hot path. When playback
        // hangs in production we have no per-chapter visibility into
        // which step stalled (chunker / engine load / first synth /
        // queue handoff). One info-level line per pipeline start gives
        // us a consistent breadcrumb at logcat capture time without
        // adding any cost in the common path.
        android.util.Log.i(
            "EnginePlayer",
            "loadAndPlay: chapter=$chapterId sentences=${sentences.size} " +
                "fromIndex=$currentSentenceIndex textChars=${text.length}",
        )
        _observableState.update {
            it.copy(
                currentFictionId = fictionId,
                currentChapterId = chapterId,
                charOffset = charOffset,
                isPlaying = true,
                bookTitle = chapter.bookTitle,
                chapterTitle = chapter.title,
                coverUri = chapter.coverUrl,
                durationEstimateMs = estimateDurationMs(text),
                currentSentenceRange = null,
                error = null,
            )
        }
        // Issue #158 — stamp the History breadcrumb right after the state
        // flips to a new currentChapterId. We log the open BEFORE the
        // pipeline starts because the row should land even if the user
        // taps a chapter and immediately backs out before audio starts
        // — that still counts as "opened" in the History tab's sense.
        // Upsert semantics mean re-opens move the row to the top
        // without creating dupes. No try/catch: a Room write failure
        // here would already crash the player on the next position-save,
        // and history is non-load-bearing for playback.
        historyRepo.logOpen(fictionId, chapterId)
        invalidateState()

        // #89 — stop the OLD pipeline FIRST, before we destroy any of
        // its engines. The pre-#89 code did `engineMutex.withLock {
        // destroy old secondaries; load new }` and only THEN called
        // startPlaybackPipeline (which internally calls
        // stopPlaybackPipeline first). That meant the old pipeline's
        // producer threads were still alive — and possibly inside a
        // JNI generateAudioPCM call on a secondary engine — when we
        // destroyed those secondaries. JNI use-after-free on the
        // native tts pointer.
        //
        // Stopping first ensures the old producer pool's
        // awaitTermination (#89 in EngineStreamingSource.close)
        // blocks until in-flight JNI calls return, so subsequent
        // destroy() calls run on idle instances. Primary singleton
        // is still protected by engineMutex (the in-loadModel
        // destroy + reload path), so this only matters for the
        // secondary instances.
        stopPlaybackPipeline()

        val loadResult: String = withContext(Dispatchers.IO) {
            // Critical: serialize loadModel against in-flight generateAudioPCM
            // by holding engineMutex (issue #11). Without it, a Piper-to-Piper
            // swap can call loadModel().destroy() and free the native `tts`
            // pointer while the prior generator's JNI generate(...) is still
            // dereferencing it on another thread → SIGSEGV. The producer
            // coroutine takes engineMutex around every generateAudioPCM, so
            // withLock here waits for the in-flight call to finish before
            // we tear the model down.
            engineMutex.withLock {
                when (active.engineType) {
                    EngineType.Piper -> {
                        // Voice swap AWAY from Kokoro/Kitten → free
                        // their secondaries. Tier 3 (#88) honors the
                        // slider for all in-process engine families
                        // now; secondaries are owned per-engine-type
                        // and torn down on type-change.
                        secondaryKokoroEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKokoroEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKittenEngines = emptyList()

                        val voiceDir = voiceManager.voiceDirFor(active.id)
                        val onnx = File(voiceDir, "model.onnx").absolutePath
                        val tokens = File(voiceDir, "tokens.txt").absolutePath
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        // PR-Tier3-Diag — log slider snapshot at pipeline-
                        // construction time so we can confirm the read is
                        // actually returning the user's value (vs. a stale
                        // 1). If you see "Tier 3 init Piper instances=N
                        // threadsPerInstance=M" in logcat with N>=2, the
                        // loop below WILL execute; if you also see no
                        // "Tier 3 secondary K loaded" lines, the loadModel
                        // is returning a non-Success status silently.
                        android.util.Log.i(
                            "EnginePlayer",
                            "Tier 3 init Piper instances=${parallelState.instances} " +
                                "threadsPerInstance=$nt onnx=${onnx.takeLast(60)}",
                        )
                        val primaryResult = VoiceEngine.getInstance()
                            .loadModel(context, onnx, tokens, nt)
                        android.util.Log.i(
                            "EnginePlayer",
                            "Tier 3 primary Piper load: result=$primaryResult",
                        )
                        // Tier 3 (#88) — slider replaces the boolean
                        // toggle. Construct (instances-1) secondaries
                        // when instances >= 2. Each gets its own
                        // OrtSession via VoxSherpa v2.7.8+'s public
                        // constructor → calls run truly in parallel.
                        // Tear down any previously-allocated set first
                        // (voice swap re-runs this path).
                        secondaryPiperEngines.forEach { runCatching { it.destroy() } }
                        secondaryPiperEngines = emptyList()
                        val secondaries = mutableListOf<com.CodeBySonu.VoxSherpa.VoiceEngine>()
                        for (i in 1 until parallelState.instances) {
                            android.util.Log.i(
                                "EnginePlayer",
                                "Tier 3 attempting secondary Piper $i",
                            )
                            val secondary = com.CodeBySonu.VoxSherpa.VoiceEngine()
                            // Propagate noiseScale settings so all
                            // instances render with the same prosody.
                            // Without this, secondaries use default
                            // 0.35/0.667 (Steady) while primary uses
                            // whatever cachedVoiceSteady dictates,
                            // causing audible mismatch between
                            // sentences depending on which instance
                            // rendered them.
                            val ns = if (cachedVoiceSteady) NOISE_SCALE_STEADY
                                     else NOISE_SCALE_EXPRESSIVE
                            val nsW = if (cachedVoiceSteady) NOISE_SCALE_W_STEADY
                                      else NOISE_SCALE_W_EXPRESSIVE
                            secondary.setNoiseScale(ns)
                            secondary.setNoiseScaleW(nsW)
                            val r = secondary.loadModel(context, onnx, tokens, nt)
                            if (r == "Success") {
                                secondaries += secondary
                                android.util.Log.i(
                                    "EnginePlayer",
                                    "Tier 3 secondary Piper $i loaded ok",
                                )
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Piper) load failed: " +
                                        "$r — capping at ${secondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryPiperEngines = secondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Kokoro -> {
                        // Voice swap AWAY from Piper/Kitten → free their
                        // secondaries. Tier 3 secondaries are
                        // engine-type-specific.
                        secondaryPiperEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryPiperEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKittenEngines = emptyList()
                        // All 53 Kokoro speakers share a single ~325MB fp32
                        // multi-speaker model. Switching speakers reuses the
                        // loaded engine; first load takes 30+s as sherpa-onnx
                        // builds the onnxruntime session and runs a warm-up
                        // generate.
                        val sharedDir = voiceManager.kokoroSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KokoroEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kokoro).speakerId,
                        )
                        // #196 — drive Kokoro's within-sentence comma
                        // pause from the same punctuation-cadence
                        // multiplier we use for between-sentence
                        // silence. 0.2f baseline = engine default; the
                        // multiplier scales it linearly so a 0× user
                        // collapses commas, a 2× user stretches them
                        // to ~0.4f. Field on the engine is read at
                        // config-build time inside loadModel, so set
                        // before loadModel — not after.
                        KokoroEngine.getInstance().setSilenceScale(
                            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
                        )
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        val primaryResult = KokoroEngine.getInstance()
                            .loadModel(context, onnx, tokens, voicesBin, nt)
                        // Tier 3 (#88) — Kokoro N-instance support.
                        // Each loaded Kokoro session is ~325 MB; on
                        // an 8 GB device 8 instances ≈ 2.6 GB which
                        // fits but is heavy. Construct sequentially —
                        // Kokoro's first-load takes ~30 s, so 8
                        // instances ≈ 4 min first-launch penalty
                        // (acceptable for an explicit opt-in;
                        // subsequent chapters reuse loaded instances).
                        secondaryKokoroEngines.forEach { runCatching { it.destroy() } }
                        secondaryKokoroEngines = emptyList()
                        val kokoroSecondaries = mutableListOf<com.CodeBySonu.VoxSherpa.KokoroEngine>()
                        for (i in 1 until parallelState.instances) {
                            val secondary = com.CodeBySonu.VoxSherpa.KokoroEngine()
                            secondary.setActiveSpeakerId(
                                (active.engineType as EngineType.Kokoro).speakerId,
                            )
                            secondary.setSilenceScale(
                                KOKORO_SILENCE_SCALE_BASELINE *
                                    currentPunctuationPauseMultiplier,
                            )
                            val r = secondary.loadModel(context, onnx, tokens, voicesBin, nt)
                            if (r == "Success") {
                                kokoroSecondaries += secondary
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Kokoro) load failed: " +
                                        "$r — capping at ${kokoroSecondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryKokoroEngines = kokoroSecondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Kitten -> {
                        // Issue #119 — Kitten parallels Kokoro: all 8
                        // Kitten speakers share a single ~25 MB fp16 ONNX
                        // multi-speaker model. Switching speakers reuses
                        // the loaded engine via setActiveSpeakerId; first
                        // load is fast (~2–4 s) because the model is tiny.
                        secondaryPiperEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryPiperEngines = emptyList()
                        secondaryKokoroEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKokoroEngines = emptyList()
                        val sharedDir = voiceManager.kittenSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KittenEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kitten).speakerId,
                        )
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        val primaryResult = KittenEngine.getInstance()
                            .loadModel(context, onnx, tokens, voicesBin, nt)
                        // Tier 3 (#88) — Kitten N-instance support.
                        // Each loaded Kitten session is small (~60–80 MB
                        // resident on the fp16 nano model), so even an
                        // 8-way fan-out fits in 1 GB. Kitten is the
                        // friendliest engine for the parallel slider on
                        // low-end hardware.
                        secondaryKittenEngines.forEach { runCatching { it.destroy() } }
                        secondaryKittenEngines = emptyList()
                        val kittenSecondaries = mutableListOf<com.CodeBySonu.VoxSherpa.KittenEngine>()
                        for (i in 1 until parallelState.instances) {
                            val secondary = com.CodeBySonu.VoxSherpa.KittenEngine()
                            secondary.setActiveSpeakerId(
                                (active.engineType as EngineType.Kitten).speakerId,
                            )
                            val r = secondary.loadModel(context, onnx, tokens, voicesBin, nt)
                            if (r == "Success") {
                                kittenSecondaries += secondary
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Kitten) load failed: " +
                                        "$r — capping at ${kittenSecondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryKittenEngines = kittenSecondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Azure -> {
                        // Tier 3 (#88) — voice swap AWAY from local
                        // engines: free all local secondaries (Piper,
                        // Kokoro, Kitten) to recover memory.
                        secondaryPiperEngines.forEach { runCatching { it.destroy() } }
                        secondaryPiperEngines = emptyList()
                        secondaryKokoroEngines.forEach { runCatching { it.destroy() } }
                        secondaryKokoroEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach { runCatching { it.destroy() } }
                        secondaryKittenEngines = emptyList()
                        // PR-4 (#183) — cloud voice activation. Nothing
                        // to load JNI-side; the "model" is the remote
                        // synthesis endpoint. Credentials gate is the
                        // only check we run here. The voices/list
                        // verify ping that PR-3's Test-connection
                        // button uses is *not* run here — synthesis
                        // itself will surface 401 on a bad key, and
                        // we don't want to add an extra HTTP round
                        // trip to every chapter start. PR-5's error
                        // handling pass elevates synth failures to
                        // typed PlaybackState errors.
                        if (!azureCredentials.isConfigured) {
                            "Error: Azure key not configured. " +
                                "Open Settings → Cloud voices to paste a key."
                        } else {
                            "Success"
                        }
                    }
                }
            }
        }
        if (loadResult != "Success") {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed("Voice load failed: $loadResult"),
                )
            }
            invalidateState()
            return
        }
        activeEngineType = active.engineType
        loadedVoiceId = active.id
        voiceReloadPending = false

        // (state was already pushed above so the spinner could show during
        // model load — refresh durationEstimate now that the active engine
        // type is known but otherwise leave state alone.)
        _observableState.update {
            it.copy(
                error = null,
            )
        }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Spin up the producer/consumer pair. Mirrors VoxSherpa standalone's
     * playback shape exactly because deviating from it has measurably
     * fuzzy output on Tab A7 Lite (issue #6):
     *
     *  - **Consumer = pinned [Thread]**, not a coroutine. `Process.set-`
     *    `ThreadPriority(URGENT_AUDIO)` is per-OS-thread; coroutines on
     *    [Dispatchers.IO] migrate threads on every suspend, so any priority
     *    bump leaks across resumptions. A dedicated thread keeps URGENT_AUDIO
     *    for the entire pipeline lifetime.
     *  - **Queue = [LinkedBlockingQueue]**, not [kotlinx.coroutines.channels.Channel].
     *    `take()` blocks the OS thread directly, no coroutine state-machine
     *    overhead, no risk of dispatcher work-stealing introducing scheduling
     *    jitter between sentences.
     *  - **Buffer = [AudioTrack.getMinBufferSize]** (set in [createAudioTrack]).
     *    Larger buffers route through AudioFlinger's deep-buffer mixer, which
     *    on Samsung tablets uses a different sample-rate-conversion path than
     *    the fast-track mixer used for `minBufferSize` tracks. Empirically the
     *    deep-buffer path adds the residual fuzz that survives the legacy
     *    `STREAM_MUSIC` constructor swap.
     *  - **[engineMutex] around `generateAudioPCM`** so a stop-then-start
     *    sequence (slider drag, voice swap, seek) never has two threads
     *    inside the singleton VoxSherpa engine at once.
     *
     * Pre-fetching is implicit via the queue's capacity — generation runs
     * ahead of playback by however many slots are free.
     */
    private fun startPlaybackPipeline() {
        // Make sure any previous run is fully stopped.
        stopPlaybackPipeline()

        val engineType = activeEngineType
        val sampleRate = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            // Issue #119 — Kitten native sample rate is 24 kHz (same as
            // Kokoro) but the runtime accessor is the source of truth.
            is EngineType.Kitten -> KittenEngine.getInstance().sampleRate
            is EngineType.Azure -> azureVoiceEngine.sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val track = createAudioTrack(sampleRate)
        audioTrack = track

        // Snapshot the user's configured queue depth at pipeline-construction
        // time. Mid-pipeline slider movements take effect on the next
        // construction (next chapter / seek / voice swap); the bounded queue
        // can't be resized live. Issue #84 — this is the LMK probe knob.
        // Cap matches BUFFER_MAX_CHUNKS (3000). The previous 1500 cap
        // silently truncated slider values above 1500, contradicting
        // the slider's 3000-chunk max — JP set the slider to 3000 to
        // probe the gap and got 1500 in practice. Lifted to 3000 so
        // the configured value reaches the queue verbatim.
        val queueCapacity = cachedBufferChunks.coerceIn(2, 3000)
        // Issue #135: snapshot the dict at construction time. The
        // capture is by-value (the dict is an immutable data class) so
        // a mid-chapter edit doesn't mutate the active pipeline's
        // substitution table — that's intentional, swapping the
        // dictionary mid-sentence would shift the pre-rendered
        // sentence text and the cache key on the next sentence,
        // producing audible drift. Edits take effect on the next
        // pipeline rebuild (seek / chapter change / voice swap /
        // speed/pitch change), exactly like the buffer-chunks knob.
        val pronunciationDict = cachedPronunciationDict
        // Tier 3 (#88) — wrap each secondary instance in the
        // VoiceEngineHandle SAM. Piper and Kokoro hand out N-1 local
        // engine instances (memory-bounded). Azure hands out N-1
        // synthetic handles that all delegate to the same singleton
        // [AzureVoiceEngine] — Azure parallelism is HTTPS-bounded, not
        // memory-bounded, so the same engine instance can fan out N
        // concurrent requests via OkHttp's connection pool. Hides
        // per-sentence latency: while sentence N plays, sentences
        // N+1..N+secondaries are synthesizing in parallel server-side.
        val secondaryHandles: List<EngineStreamingSource.VoiceEngineHandle> = when (engineType) {
            EngineType.Piper -> secondaryPiperEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    override val sampleRate: Int =
                        eng.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            is EngineType.Kokoro -> secondaryKokoroEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    override val sampleRate: Int =
                        eng.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            // Issue #119 — Kitten secondaries. Same wrapping shape as
            // Kokoro because both engines are multi-speaker singletons
            // with the same `generateAudioPCM(text, speed, pitch)` Java
            // signature.
            is EngineType.Kitten -> secondaryKittenEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    override val sampleRate: Int =
                        eng.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            is EngineType.Azure -> {
                // Reuse the parallelSynthInstances knob as Azure
                // lookahead depth — local engines and Azure both gain
                // from the same "fan out N concurrent producers"
                // pattern, even though their costs differ (Azure is
                // HTTPS-bounded, not memory-bounded). A future PR
                // could split the slider if the tradeoffs diverge
                // visibly.
                val lookaheadCount = (cachedParallelSynthInstances - 1).coerceAtLeast(0)
                val voiceName = engineType.voiceName
                List(lookaheadCount) {
                    object : EngineStreamingSource.VoiceEngineHandle {
                        override val sampleRate: Int =
                            azureVoiceEngine.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                        override fun generateAudioPCM(
                            text: String, speed: Float, pitch: Float,
                        ): ByteArray? {
                            AndroidProcess.setThreadPriority(
                                AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                            )
                            return azureVoiceEngine.synthesize(text, voiceName, speed, pitch)
                        }
                    }
                }
            }
            else -> emptyList()
        }
        // PR-D (#86) — build the cache key for this (chapter, voice,
        // speed, pitch, dict) tuple. All five pieces of identity must
        // be known; if any is null we skip the cache write entirely
        // (the source gets cacheAppender = null and behaves as
        // pre-PR-D). Captured here at pipeline-construction time so a
        // mid-pipeline state mutation can't shift the key out from
        // under the live appender.
        val chapterIdForCache = _observableState.value.currentChapterId
        val voiceIdForCache = loadedVoiceId
        val cacheKey: PcmCacheKey? = if (
            chapterIdForCache != null && voiceIdForCache != null
        ) {
            PcmCacheKey(
                chapterId = chapterIdForCache,
                voiceId = voiceIdForCache,
                speedHundredths = PcmCacheKey.quantize(currentSpeed),
                pitchHundredths = PcmCacheKey.quantize(currentPitch),
                chunkerVersion = CHUNKER_VERSION,
                pronunciationDictHash = pronunciationDict.contentHash,
            )
        } else null

        // PR-E (#86) — cache-hit dispatch. If the cache for this key
        // is COMPLETE (the .idx.json sidecar landed from a previous
        // play's natural end), open a CacheFileSource and skip the
        // engine pipeline entirely. The consumer thread treats the
        // source uniformly via the PcmSource interface; cached
        // chapters get instant first-byte + zero inter-sentence gaps.
        //
        // Touch the .pcm mtime so LRU eviction in PcmCache.evictTo
        // prefers genuinely-cold entries (sort key: mtime ascending
        // within (isAzure) groups).
        //
        // On CacheFileSource.open failure (corrupt index, truncated
        // pcm) we fall through to the streaming path. The next
        // natural-end finalize will overwrite the bad entry with
        // fresh bytes; a corrupt cache is a re-render trigger, not
        // a crash. We log the failure so the cache-hit/miss ratio
        // observability stays meaningful in logcat — adb run-as is
        // gone post-isDebuggable=false, so logcat is the primary
        // verification surface for cache behavior on the tablet.
        val cacheHitSource: PcmSource? = cacheKey?.let { key ->
            runBlocking {
                if (!pcmCache.isComplete(key)) return@runBlocking null
                pcmCache.touch(key)
                runCatching {
                    CacheFileSource.open(
                        pcmFile = pcmCache.pcmFileFor(key),
                        indexFile = pcmCache.indexFileFor(key),
                        startSentenceIndex = currentSentenceIndex,
                    )
                }.onSuccess {
                    android.util.Log.i(
                        "EnginePlayer",
                        "pcm-cache HIT chapter=${key.chapterId} voice=${key.voiceId} " +
                            "speed=${key.speedHundredths} pitch=${key.pitchHundredths} " +
                            "fromSentence=$currentSentenceIndex base=${key.fileBaseName().take(12)}",
                    )
                }.onFailure { t ->
                    android.util.Log.w(
                        "EnginePlayer",
                        "pcm-cache hit-open FAILED chapter=${key.chapterId} " +
                            "base=${key.fileBaseName().take(12)} — falling back to streaming",
                        t,
                    )
                }.getOrNull()
            }
        }

        val source: PcmSource = if (cacheHitSource != null) {
            cacheHitSource
        } else {
            // Cache miss (or hit-open failed) — streaming source +
            // tee appender path (PR-D). If a partial entry exists
            // from a prior killed render (meta.json on disk,
            // idx.json absent), wipe it first; PR-D's resume policy
            // is "abandon, restart".
            //
            // runBlocking is acceptable here: startPlaybackPipeline
            // is already called synchronously from a coroutine
            // (or from suspend functions like loadAndPlay), so the
            // blocking wait for pcmCache.delete is brief (a few
            // File.delete syscalls on Dispatchers.IO — single-digit
            // ms).
            val appender: PcmAppender? = cacheKey?.let { key ->
                runBlocking {
                    if (pcmCache.metaFileFor(key).exists() && !pcmCache.isComplete(key)) {
                        // Stale partial — wipe before opening fresh.
                        pcmCache.delete(key)
                    }
                    pcmCache.appender(key, sampleRate = sampleRate)
                }
            }
            cacheKey?.let { key ->
                android.util.Log.i(
                    "EnginePlayer",
                    "pcm-cache MISS chapter=${key.chapterId} voice=${key.voiceId} " +
                        "speed=${key.speedHundredths} pitch=${key.pitchHundredths} " +
                        "fromSentence=$currentSentenceIndex base=${key.fileBaseName().take(12)}",
                )
            }
            EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = currentSentenceIndex,
                engine = activeVoiceEngineHandle(engineType),
                speed = currentSpeed,
                pitch = currentPitch,
                engineMutex = engineMutex,
                cacheAppender = appender,
                punctuationPauseMultiplier = currentPunctuationPauseMultiplier,
                // #486 / #488 — TalkBack inter-sentence pad. Snapshot
                // at pipeline-construction time; mid-listen slider
                // edits take effect on next rebuild (matches the
                // other live-config knobs). The volatile cache is
                // kept in sync by [observeA11yPacing].
                extraA11ySilenceMs = cachedA11yExtraSilenceMs,
                queueCapacity = queueCapacity,
                pronunciationDictApply = pronunciationDict::apply,
                secondaryEngines = secondaryHandles,
            )
        }
        pcmSource = source
        pipelineRunning.set(true)
        // Issue #290 — reset the frames-written tally so the debug
        // overlay's `audio buffered ms` reads against the fresh
        // AudioTrack's playbackHeadPosition rather than a stale total
        // from the previous pipeline.
        totalFramesWritten = 0L
        // Clear the prev-chunk-end anchor so the first chunk of this
        // pipeline lifetime doesn't get a "gap" attributed to user
        // pause time, seek time, or model load time.
        chunkGapLogger.resetForNewPipeline()

        consumerThread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            var naturalEnd = false
            var firstSentence = true
            // Track AudioTrack pause state so the buffer-low check below can
            // toggle play/pause without thrashing JNI on every iteration.
            // Consumer is the only thread that calls track.play / track.pause
            // for streaming-source playback (user-initiated pauses go through
            // stopPlaybackPipeline, which tears the track down).
            var paused = false
            // Live track volume mirror. Scoped to the consumer thread (not
            // per-sentence) so the per-write change-detection skips the
            // setVolume JNI call when the ramp is idle, which is the steady
            // state. Seeded in the firstSentence block below.
            var lastVol = -1f
            try {
                while (pipelineRunning.get()) {
                    // BEFORE pulling the next chunk, check whether buffer
                    // recovered above the resume threshold. If so, kick
                    // AudioTrack alive again so the next write lands in a
                    // playing track rather than a paused-then-played one.
                    //
                    // Issue #98 — Mode B gate. With Catch-up Pause off, this
                    // branch never fires (paused is never true; see the
                    // matching gate on the underrun pause branch below). The
                    // `paused &&` short-circuit is the same fast-path either
                    // way; the cached read is just for symmetry with the
                    // pause branch.
                    if (cachedCatchupPause &&
                        !source.isStreaming &&
                        paused &&
                        source.bufferHeadroomMs.value >= BUFFER_RESUME_THRESHOLD_MS) {
                        runCatching { track.play() }
                        paused = false
                        scope.launch {
                            _observableState.update { it.copy(isBuffering = false) }
                        }
                    }

                    // runBlocking on the dedicated audio thread — same shape
                    // as the prior runInterruptible bridge. The thread stays
                    // pinned at URGENT_AUDIO; nextChunk's runInterruptible
                    // dispatches the take() to Dispatchers.IO under the hood,
                    // but because the consumer thread is the only thing
                    // waiting for the result, runBlocking just parks it
                    // exactly as queue.take() did pre-PR-A.
                    val chunk = try {
                        runBlocking { source.nextChunk() }
                    } catch (_: Throwable) {
                        null
                    }
                    if (chunk == null) {
                        // null = source exhausted (chapter end) OR closed
                        // by stopPlaybackPipeline. Distinguish by the run
                        // flag: if we're still meant to be running, the
                        // null is an honest end-of-chapter.
                        naturalEnd = pipelineRunning.get()
                        break
                    }

                    // Surface the new sentence range BEFORE writing. Fire-
                    // and-forget on Main — withContext would force this
                    // thread to coordinate with the coroutine dispatcher,
                    // which is the whole reason we left coroutines.
                    scope.launch {
                        currentSentenceIndex = chunk.sentenceIndex
                        _observableState.update {
                            it.copy(
                                currentSentenceRange = chunk.range,
                                charOffset = chunk.range.startCharInChapter,
                            )
                        }
                    }

                    if (firstSentence) {
                        val v = volumeRamp.current
                        runCatching { track.setVolume(v) }
                        lastVol = v
                        runCatching { track.play() }
                        firstSentence = false
                    }

                    // After the take, headroom dropped by this chunk's audio
                    // duration. If we just crossed below the underrun
                    // threshold AND we're not already paused, pause the
                    // AudioTrack and surface the buffering UI BEFORE we
                    // start writing — the OS hardware buffer is large
                    // enough on this device (~2-3 s deep) that the next
                    // write would land seconds before the listener hears
                    // silence, so the buffering UI needs to lead the audio
                    // by that margin.
                    //
                    // Issue #98 — Mode B gate. With Catch-up Pause off, the
                    // consumer drains through underruns without pausing:
                    // listener may hear dead air, but never sees the
                    // "Buffering…" UI. EngineStreamingSource is untouched.
                    if (cachedCatchupPause &&
                        !source.isStreaming &&
                        !paused &&
                        source.bufferHeadroomMs.value < BUFFER_UNDERRUN_THRESHOLD_MS) {
                        runCatching { track.pause() }
                        paused = true
                        scope.launch {
                            _observableState.update { it.copy(isBuffering = true) }
                        }
                    }

                    // While paused, DO NOT write into the AudioTrack —
                    // a paused MODE_STREAM track does not drain its ring
                    // buffer, so AudioTrack.write() returns 0 once the
                    // buffer is full and the inner write loop spins at
                    // URGENT_AUDIO priority forever (regression introduced
                    // in PR #77, only surfaces with sub-realtime voices
                    // like Piper-high on slow CPUs where headroom drops
                    // below the underrun threshold). Park the consumer on
                    // the headroom flow until the producer queues enough
                    // audio to cross the resume threshold, then resume
                    // the track and proceed with the write.
                    if (paused) {
                        try {
                            runBlocking {
                                source.bufferHeadroomMs.first {
                                    it >= BUFFER_RESUME_THRESHOLD_MS || !pipelineRunning.get()
                                }
                            }
                        } catch (_: Throwable) {
                            // Interrupted by stopPlaybackPipeline (interrupt +
                            // close). The pipelineRunning check below will
                            // skip the resume; the outer while will exit.
                        }
                        if (pipelineRunning.get()) {
                            runCatching { track.play() }
                            paused = false
                            scope.launch {
                                _observableState.update { it.copy(isBuffering = false) }
                            }
                        }
                    }

                    // Stamp the start of the AudioTrack-write phase for this
                    // chunk. Combined with the chunkEnd() below, this lets
                    // the perf lane log gap_ms = startN - endNm1, which is
                    // the audible silence between adjacent chunks (modulo
                    // the constant ~130 ms minBuffer latency, see
                    // ChunkGapLogger doc). No-op unless the marker file is
                    // present, so this is free in normal operation.
                    val gapVoiceId = loadedVoiceId ?: "unknown"
                    chunkGapLogger.chunkStart(gapVoiceId, chunk.sentenceIndex)

                    // Apply the SleepTimer fade-out ramp to the live track.
                    // Polled per write iteration; AudioTrack.setVolume is a
                    // cheap JNI call but the lastVol guard skips it entirely
                    // when the ramp is idle (steady state).
                    var written = 0
                    while (written < chunk.pcm.size && pipelineRunning.get()) {
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val n = track.write(chunk.pcm, written, chunk.pcm.size - written)
                        if (n < 0) break // error code from AudioTrack
                        written += n
                        // Issue #290 — frames written tally for the
                        // debug overlay's "audio buffered ms" row.
                        // 16-bit mono PCM = 2 bytes per frame.
                        if (n > 0) totalFramesWritten += n / 2
                    }
                    // Spool trailing silence from a shared zero-filled
                    // buffer (no per-sentence allocation).
                    var remaining = chunk.trailingSilenceBytes
                    while (remaining > 0 && pipelineRunning.get()) {
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val sz = remaining.coerceAtMost(SILENCE_CHUNK.size)
                        val n = track.write(SILENCE_CHUNK, 0, sz)
                        if (n < 0) break
                        remaining -= n
                        // Silence frames count toward the buffer too.
                        if (n > 0) totalFramesWritten += n / 2
                    }

                    // End-of-chunk: AudioTrack has accepted every byte of
                    // pcm + trailing silence. The next iteration's blocking
                    // queue.take() is where a slow producer shows up as a
                    // logged gap.
                    chunkGapLogger.chunkEnd(gapVoiceId, chunk.sentenceIndex)

                    // Argus Fix B (#79) — decrement the source's headroom
                    // tracker NOW, not when we dequeued. The chunk just
                    // entered AudioTrack's hardware ring buffer; the
                    // listener is about to hear it (or already hearing
                    // it). Decrementing here makes `bufferHeadroomMs`
                    // reflect "audio not yet heard," which is what the
                    // underrun threshold actually wants to compare
                    // against. Pre-Fix-B the decrement fired at dequeue,
                    // making the trigger pessimistic by ~one chunk.
                    source.decrementHeadroomForChunk(chunk)
                }
            } finally {
                // Release the AudioTrack from the same thread that owns
                // its write() loop. Doing this from the main thread (as
                // [stopPlaybackPipeline] used to) raced with whatever
                // write() was in flight and could JNI-crash. Now release
                // happens *after* the write loop has definitely exited.
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                // Don't leave the UI stuck in "Buffering..." after we've
                // shut down — pause/stop tears the pipeline down via
                // stopPlaybackPipeline which sets isPlaying=false; we
                // additionally clear isBuffering so a subsequent resume
                // that builds a fresh pipeline starts from a clean slate.
                if (paused) {
                    scope.launch {
                        _observableState.update { it.copy(isBuffering = false) }
                    }
                }
                if (naturalEnd && pipelineRunning.get()) {
                    // PR-D (#86) — finalize the cache on natural end so
                    // the index sidecar lands and the cache is complete
                    // for next play. Must happen BEFORE the chapter-done
                    // coroutine because handleChapterDone advances and
                    // calls loadAndPlay → startPlaybackPipeline, which
                    // constructs a NEW source and overwrites the field.
                    // We call finalizeCache on the SAME `source` local
                    // captured by the Thread closure, so the field
                    // reassignment doesn't affect us.
                    runCatching { source.finalizeCache() }
                    // Eviction runs AFTER finalize so the just-finalized
                    // entry isn't visible to evictTo as an oldest LRU
                    // candidate (it has the freshest mtime). The pinned
                    // set is the just-finalized basename — defense in
                    // depth in case mtime granularity makes it look
                    // "old" relative to a same-second-finalized
                    // neighbor. Runs on scope (Main+SupervisorJob); the
                    // suspend body delegates to Dispatchers.IO inside
                    // PcmCache.evictTo.
                    scope.launch {
                        runCatching {
                            pcmCache.evictToQuota(
                                pinnedBasenames = cacheKey?.let { setOf(it.fileBaseName()) }
                                    ?: emptySet(),
                            )
                        }
                    }
                    scope.launch {
                        sleepTimer.signalChapterEnd()
                        handleChapterDone()
                    }
                }
            }
        }, "storyvox-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    /** Bridge to the singleton VoxSherpa engines via the [EngineStreamingSource]
     *  SAM. Lives here (not in the source module) so EnginePlayer can switch
     *  on the active engine type and EngineStreamingSource stays
     *  test-friendly without depending on VoxSherpa AARs. */
    private fun activeVoiceEngineHandle(engineType: EngineType?): EngineStreamingSource.VoiceEngineHandle =
        when (engineType) {
            is EngineType.Azure -> azureStreamingHandle(engineType)
            else -> object : EngineStreamingSource.VoiceEngineHandle {
                override val sampleRate: Int = when (engineType) {
                    is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
                    // Issue #119 — Kitten dispatch.
                    is EngineType.Kitten -> KittenEngine.getInstance().sampleRate
                    else -> VoiceEngine.getInstance().sampleRate
                }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

                override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                    AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                    return when (engineType) {
                        is EngineType.Kokoro -> KokoroEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                        // Issue #119 — Kitten dispatch.
                        is EngineType.Kitten -> KittenEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                        else -> VoiceEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                    }
                }
            }
        }

    /**
     * Streaming-capable Azure handle. Implements the streaming
     * variant so [EngineStreamingSource] can take the
     * startStreamingSerialProducer path when no Tier 3 secondaries
     * are wired (i.e. parallelSynthInstances == 1). With secondaries
     * the parallel path runs the non-streaming generateAudioPCM —
     * lookahead wins over streaming when both could apply (the user
     * gets sentences N+1..N+k pre-rendered in parallel; streaming
     * helps sentence 1 alone).
     */
    private fun azureStreamingHandle(
        engineType: EngineType.Azure,
    ): EngineStreamingSource.StreamingVoiceEngineHandle =
        object : EngineStreamingSource.StreamingVoiceEngineHandle {
            override val sampleRate: Int = azureVoiceEngine.sampleRate
                .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                return azureVoiceEngine.synthesize(text, engineType.voiceName, speed, pitch)
            }

            override fun generateAudioPCMStream(
                text: String, speed: Float, pitch: Float,
            ): kotlinx.coroutines.flow.Flow<ByteArray> {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                return azureVoiceEngine.synthesizeStreaming(
                    text, engineType.voiceName, speed, pitch,
                )
            }
        }

    private fun stopPlaybackPipeline() {
        // Shutdown handshake:
        //  1. Signal stop via the run flag.
        //  2. Pause + flush the AudioTrack so any currently-blocked
        //     write() returns immediately (ring buffer has space again).
        //  3. Close the source (cancels its producer; offers the END
        //     pill so the consumer's nextChunk returns null promptly).
        //  4. Interrupt + join the consumer thread (so a runBlocking
        //     parked inside nextChunk wakes up).
        //  5. The consumer's own finally block does the AudioTrack
        //     release — *not* main thread — so we can't race a write().
        //     If join times out we still don't release ourselves; the
        //     consumer will finish its current write and clean up
        //     whenever it gets there.
        pipelineRunning.set(false)

        val track = audioTrack
        audioTrack = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }

        val src = pcmSource
        pcmSource = null
        if (src != null) {
            // close() is suspending but synchronous in body — runBlocking
            // here is fine; we're on Main and the close path doesn't
            // dispatch.
            runBlocking { src.close() }
        }

        val t = consumerThread
        consumerThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            // 2 s upper bound — plenty for a paused+flushed track to
            // finish its current write iteration. If we somehow exceed
            // this the consumer keeps living until it's done; the OLD
            // track's release is its responsibility.
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }
    }

    /** Build an AudioTrack that mirrors the standalone VoxSherpa demo exactly:
     *  legacy `STREAM_MUSIC` constructor, `getMinBufferSize()` buffer (no
     *  multiplier), `MODE_STREAM`. This is deliberate even though it costs us
     *  the seconds-long pre-render cushion the bigger buffer provided.
     *
     *  Why minBuffer specifically: AudioFlinger has two media mixer paths.
     *  Tracks created with a buffer ≈ minBufferSize qualify for the
     *  fast-track (low-latency) mixer; larger buffers route through the
     *  deep-buffer mixer. On Samsung tablets the deep-buffer mixer uses a
     *  different sample-rate-conversion chain that introduces audible
     *  distortion on 22050/24000Hz mono speech PCM. The bigger buffer
     *  bought us ~2 seconds of generator headroom but lost us clean output;
     *  the producer now keeps the generator queue full ([QUEUE_CAPACITY]
     *  sentences ahead) so the small buffer never empties between writes.
     *
     *  We also keep the legacy constructor (vs `AudioTrack.Builder`) because
     *  on Samsung the Builder path with `USAGE_MEDIA + CONTENT_TYPE_MUSIC`
     *  goes through the AudioAttributes routing layer, which on some firmware
     *  applies SoundAlive/Atmos. The legacy ctor advertises `STREAM_MUSIC`
     *  directly to AudioFlinger and bypasses those effects on the affected
     *  devices. VoxSherpa standalone does the same.
     *
     *  ROADMAP A/B: temporarily routable through [createAudioTrackBuilder]
     *  via a runtime toggle. Touch the file at
     *  `${context.filesDir}/audiotrack-builder` (e.g.
     *  `adb shell touch /data/data/in.jphe.storyvox/files/audiotrack-builder`)
     *  to switch to the Builder + AudioAttributes path on the next pipeline
     *  rebuild. Remove the file to flip back. The toggle is checked on
     *  every [createAudioTrack] call — every play()/seek/setSpeed/voice-swap
     *  rebuilds the AudioTrack — so JP can A/B in-place without an app
     *  restart, and definitely without a rebuild. The Builder path will
     *  ship as the default once we confirm clean output on Tab A7 Lite;
     *  this entire dual-path block is deleted in that follow-up PR. */
    @Suppress("DEPRECATION")
    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        if (isBuilderPathEnabled()) return createAudioTrackBuilder(sampleRate)
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            encoding,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )
    }

    /** Modern AudioTrack.Builder + AudioAttributes(USAGE_MEDIA,
     *  CONTENT_TYPE_MUSIC) path. Same channel mask / encoding / buffer size
     *  as the legacy ctor so the only behavioural difference under test is
     *  the AudioAttributes routing layer.
     *
     *  Why CONTENT_TYPE_MUSIC and not CONTENT_TYPE_SPEECH: on Tab A7 Lite,
     *  CONTENT_TYPE_SPEECH triggered Samsung's speech-DSP path back in
     *  v0.4.9 — we documented the symptom (audible distortion) and moved
     *  off it. The legacy STREAM_MUSIC ctor already advertises music to
     *  AudioFlinger, and we're trying to match that behaviour through the
     *  modern API.
     *
     *  Outcome 1 — Builder sounds clean on Tab A7 Lite: the cleanup PR
     *  deletes the legacy ctor + the @Suppress("DEPRECATION") + this entire
     *  toggle block, and the `createAudioTrack(sampleRate)` body becomes
     *  just the Builder call.
     *
     *  Outcome 2 — Builder reintroduces fuzz: this dual-path stays as a
     *  diagnostic for future devices, but the default toggle stays off,
     *  and we file an issue documenting WHICH USAGE × CONTENT_TYPE combos
     *  we tested. The legacy ctor stays load-bearing. */
    private fun createAudioTrackBuilder(sampleRate: Int): AudioTrack {
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val attrs = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AndroidAudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Runtime toggle for the AudioTrack.Builder A/B test. Checked on every
     *  AudioTrack rebuild so flipping the marker file takes effect on the
     *  next play()/seek/setSpeed/voice-swap with no app restart needed.
     *  Marker lives in app-private storage so no permissions are required:
     *
     *  ```
     *  adb shell touch /data/data/in.jphe.storyvox/files/audiotrack-builder
     *  ```
     *
     *  Remove the file to flip back to the legacy ctor.
     *
     *  Failure-tolerant: if `filesDir` is somehow unavailable (it never
     *  is in practice — Android creates it on first context use) we fall
     *  back to the legacy path. The whole toggle is dead code once the A/B
     *  resolves. */
    private fun isBuilderPathEnabled(): Boolean = try {
        java.io.File(context.filesDir, "audiotrack-builder").exists()
    } catch (_: Throwable) {
        false
    }

    /**
     * Routed pause — first user-facing surface (play-screen button →
     * PlaybackController.pause()). For audio-stream chapters (#373,
     * KVMR live + the v0.5.32 radio backend), this drops `playWhenReady`
     * on the sibling ExoPlayer so the stream actually pauses. For TTS
     * chapters it tears down the TTS pipeline via [pauseTts].
     *
     * The TTS-only path used to be the sole pause surface — clicking
     * Pause while listening to radio would tear down the (idle) TTS
     * pipeline and the ExoPlayer would happily keep streaming on top
     * of it. The Media3 `handleSetPlayWhenReady` command already did
     * the routing correctly, but the play-screen Pause button bypassed
     * it via PlaybackController → player.pauseTts(). Bug surfaced on
     * v0.5.36 on tablet/Flip3 with the Radio backend.
     */
    /**
     * Note: not named `pause()` — that collides with `BasePlayer.pause()`
     * which Media3 routes through `handleSetPlayWhenReady(false)`. Using
     * `pauseRouted()` keeps PlaybackController off the Media3 command-
     * dispatch path while preserving the same routing semantics.
     */
    fun pauseRouted() {
        if (_observableState.value.isLiveAudioChapter) {
            audioStreamPlayer?.let { p ->
                p.playWhenReady = false
                _observableState.update { it.copy(isPlaying = false) }
                invalidateState()
            }
            return
        }
        pauseTts()
    }

    fun pauseTts() {
        _observableState.update { it.copy(isPlaying = false) }
        stopPlaybackPipeline()
        scope.launch { persistPosition() }
        invalidateState()
    }

    fun resume() {
        // Audio-stream chapter — bring ExoPlayer back online. Mirror of
        // the Media3 handleSetPlayWhenReady(true) branch so the play-
        // screen Play button works on radio chapters too. Returns early
        // before the TTS-specific guards below; sentences[] is empty
        // for audio-stream chapters by design.
        if (_observableState.value.isLiveAudioChapter) {
            audioStreamPlayer?.let { p ->
                p.playWhenReady = true
                _observableState.update { it.copy(isPlaying = true) }
                invalidateState()
            }
            return
        }
        if (sentences.isEmpty()) return
        // If the user activated a different voice while paused (#8), the
        // existing engine model is the wrong one — route through loadAndPlay
        // to swap it before any audio comes out.
        if (voiceReloadPending) {
            val s = _observableState.value
            val fictionId = s.currentFictionId
            val chapterId = s.currentChapterId
            if (fictionId != null && chapterId != null) {
                voiceReloadPending = false
                scope.launch { loadAndPlay(fictionId, chapterId, s.charOffset) }
                return
            }
            voiceReloadPending = false
        }
        _observableState.update { it.copy(isPlaying = true, error = null) }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * #120 — step to the previous (direction=-1) or next (direction=+1)
     * sentence boundary. Wraps the same seek path used by
     * [seekToCharOffset] / tap-to-seek; the producer restarts at the
     * new sentence, the brass underline + reader auto-scroll move
     * with it. Clamps at chapter boundaries — no-op if we're on
     * sentence 0 and direction=-1, same for last sentence + direction=+1.
     */
    fun seekSentence(direction: Int) {
        if (sentences.isEmpty()) return
        val targetIndex = (currentSentenceIndex + direction)
            .coerceIn(0, sentences.size - 1)
        if (targetIndex == currentSentenceIndex) return
        val target = sentences[targetIndex]
        seekToCharOffset(target.startChar)
    }

    fun seekToCharOffset(offset: Int) {
        if (sentences.isEmpty()) return
        val clamped = offset.coerceAtLeast(0)
        val target = sentences.indexOfLast { it.startChar <= clamped }
            .takeIf { it >= 0 } ?: 0
        currentSentenceIndex = target
        val s = sentences[target]
        // Issue #7: also update currentSentenceRange so the brass underline
        // and auto-scroll move to the tapped sentence — previously only
        // charOffset moved, leaving the visual highlight stale.
        _observableState.update {
            it.copy(
                charOffset = s.startChar,
                currentSentenceRange = SentenceRange(s.index, s.startChar, s.endChar),
            )
        }
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    suspend fun advanceChapter(direction: Int) {
        val current = _observableState.value.currentChapterId ?: return
        val fiction = _observableState.value.currentFictionId ?: return
        val nextId = if (direction >= 0) {
            chapterRepo.getNextChapterId(current)
        } else {
            chapterRepo.getPreviousChapterId(current)
        } ?: run {
            if (direction >= 0) _uiEvents.tryEmit(PlaybackUiEvent.BookFinished)
            return
        }
        chapterRepo.queueChapterDownload(fiction, nextId, requireUnmetered = false)
        chapterRepo.observeChapter(nextId).filterNotNull().first()
        loadAndPlay(fiction, nextId, charOffset = 0)
        // Issue #287 — persist the new chapter's id immediately so the
        // Library "Continue listening" join sees the freshly-loaded
        // chapter on its next emission. Without this the playback_position
        // row stays pointed at the PREVIOUS chapter until the next save
        // tick (e.g. user pauses, or next sentence boundary triggers a
        // persistPosition), and the Resume card paints the new chapter's
        // title alongside the old chapter's index/number — a confusing
        // mismatch every auto-advance.
        persistPosition()
        _uiEvents.tryEmit(PlaybackUiEvent.ChapterChanged(nextId))
    }

    private suspend fun handleChapterDone() {
        val chapterId = _observableState.value.currentChapterId
        val fictionId = _observableState.value.currentFictionId
        persistPosition()
        if (chapterId != null) chapterRepo.markChapterPlayed(chapterId)
        // PR-F (#86) — schedule chapter N+2's background render. N+1 is
        // either already cached, in flight as the previous chapter-done
        // trigger's enqueue, or about to be teed by PR-D as the user
        // taps Next. Wrapped in runCatching so a scheduler hiccup
        // doesn't block the natural-end flow (advanceChapter, history
        // marker, ChapterDone event).
        if (chapterId != null) {
            runCatching { prerenderTriggers.onChapterCompleted(chapterId) }
        }
        // Issue #158 — piggyback the History `completed` flag on the
        // existing end-of-chapter event. Mirrors `markChapterPlayed`
        // above; both fire on the same trigger (chapter naturally
        // ended, not user-skipped via Next). `fractionRead = 1f`
        // because we reached end-of-chapter — the only call-site that
        // passes a partial fraction would be a future "user skipped
        // 90% of the way through" path, which isn't in scope for this
        // issue.
        if (fictionId != null && chapterId != null) {
            historyRepo.markCompleted(fictionId, chapterId, fraction = 1f)
        }
        // Calliope (v0.5.00) — distinguish "chapter naturally finished" from
        // "user tapped Next chapter". Emit BEFORE advanceChapter so the
        // confetti overlay's observer sees ChapterDone first; the
        // subsequent ChapterChanged (from inside advanceChapter) is a
        // separate axis. UI is responsible for the one-time gate; the
        // engine just announces facts.
        if (chapterId != null) {
            _uiEvents.tryEmit(PlaybackUiEvent.ChapterDone(chapterId))
        }
        // advanceChapter now persists the new chapter's id internally
        // (issue #287 — see the comment on advanceChapter).
        advanceChapter(direction = 1)
    }

    private suspend fun persistPosition() {
        val s = _observableState.value
        val fictionId = s.currentFictionId ?: return
        val chapterId = s.currentChapterId ?: return
        positionRepo.save(
            fictionId = fictionId,
            chapterId = chapterId,
            charOffset = s.charOffset,
            durationEstimateMs = s.durationEstimateMs,
        )
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        _observableState.update { it.copy(speed = speed) }
        if (_observableState.value.isPlaying) startPlaybackPipeline() // rebuild with new speed
        invalidateState()
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        _observableState.update { it.copy(pitch = pitch) }
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Issue #90 — set the inter-sentence silence multiplier. 0f disables
     * the splice entirely; 1f restores the default. Coerced to [0, 4] to
     * defend against bad callers (the UI hard-codes Off=0 / Normal=1 /
     * Long=1.75 today, but a future slider could pass arbitrary values).
     *
     * Mirrors [setSpeed] / [setPitch]: stores the new value, then rebuilds
     * the pipeline so the next sentence the producer generates picks up
     * the new multiplier. The currently-playing sentence finishes with
     * whatever silence it was queued with — we don't try to retro-edit
     * audio already in the AudioTrack ring buffer.
     */
    fun setPunctuationPauseMultiplier(multiplier: Float) {
        currentPunctuationPauseMultiplier = multiplier.coerceIn(0f, 4f)
        // #196 — push the new scale into the Kokoro engine immediately
        // so within-sentence comma pauses take effect on the next
        // generated sentence. The setter triggers an OfflineTts
        // rebuild via _reloadIfActive (VoxSherpa v2.7.6+); the active
        // sentence finishes with the old scale, the next one picks up
        // the new value. No-op on Piper voices — VoiceEngine doesn't
        // expose silenceScale because the issue is Kokoro-specific.
        KokoroEngine.getInstance().setSilenceScale(
            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
        )
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    fun setTtsVolume(v: Float) {
        volumeRamp.set(v)
        runCatching { audioTrack?.setVolume(v.coerceIn(0f, 1f)) }
    }

    private fun estimateDurationMs(text: String): Long {
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * currentSpeed
        if (charsPerSec <= 0f) return 0L
        return ((text.length / charsPerSec) * 1000f).toLong()
    }

    // ----- SimpleBasePlayer command handlers -----

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Issue #373 — audio-stream mode (KVMR live + future LibriVox
        // tracks) routes play/pause through the sibling ExoPlayer, not
        // the TTS pipeline. The TTS pause/resume path tears down the
        // PCM producer + AudioTrack; doing that to a live-stream
        // chapter would silently lose the stream connection.
        if (_observableState.value.isLiveAudioChapter) {
            val p = audioStreamPlayer
            if (p != null) {
                p.playWhenReady = playWhenReady
                _observableState.update { it.copy(isPlaying = playWhenReady) }
                invalidateState()
            }
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) resume() else pauseTts()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // Issue #373 — stop both pipelines defensively; whichever was
        // running drops, the other is a cheap no-op.
        stopPlaybackPipeline()
        stopAudioStreamPlayer()
        _observableState.update {
            it.copy(
                isPlaying = false,
                currentSentenceRange = null,
                isLiveAudioChapter = false,
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        setPitch(playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseEngine()
        return Futures.immediateVoidFuture()
    }

    fun releaseEngine() {
        kotlinx.coroutines.runBlocking { persistPosition() }
        stopRecapPipeline()
        stopPlaybackPipeline()
        stopAudioStreamPlayer()
        scope.cancel()
    }

    // ─── audio-stream playback (#373) ─────────────────────────────────

    /**
     * Issue #373 — sibling Media3 [androidx.media3.exoplayer.ExoPlayer]
     * for audio-stream chapters (KVMR community radio + future
     * LibriVox / Internet Archive). Lazily constructed on first
     * audio-routed `loadAndPlay` and reused across chapter swaps; torn
     * down on [handleRelease] / [handleStop].
     *
     * Lives alongside the TTS pipeline rather than replacing it because
     * the bulk of EnginePlayer's machinery (sentence chunker, VoxSherpa
     * engines, Sonic pitch shifting, AudioTrack lifecycle) is purely
     * text-narration concerns that don't apply to a pre-rendered audio
     * stream. The two paths are mutually exclusive on a single chapter
     * load — guarded by [PlaybackState.isLiveAudioChapter] on every
     * branch in [handleSetPlayWhenReady] / [handleStop] / playback
     * controllers that share this player instance.
     */
    private var audioStreamPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    /** Listener attached to [audioStreamPlayer] so isPlaying flips on
     *  the MediaSession surface follow real player events (buffer →
     *  ready → playing). Held so [stopAudioStreamPlayer] can detach
     *  before releasing the player and avoid the leaked-callback
     *  warning on a fresh chapter load. */
    private var audioStreamListener: androidx.media3.common.Player.Listener? = null

    /**
     * Issue #373 — load and play an audio-stream chapter through
     * Media3's [androidx.media3.exoplayer.ExoPlayer]. Bypasses the TTS
     * pipeline entirely; the URL goes straight to ExoPlayer's
     * MediaItem builder, which handles AAC / MP3 / OGG / streaming
     * containers natively. Caller has already verified
     * `chapter.audioUrl != null`.
     *
     * No sentence chunker / charOffset semantics — a live stream has
     * no positional addressing the user can scrub against. The
     * `charOffset` argument from [loadAndPlay] is ignored for audio
     * chapters; resume after pause restarts the stream from "now".
     */
    private suspend fun loadAndPlayAudioStream(
        fictionId: String,
        chapterId: String,
        chapter: PlaybackChapter,
    ) {
        val url = chapter.audioUrl
            ?: return // shouldn't happen — caller guards on non-null
        // Tear down any in-flight TTS pipeline; the two paths can't
        // coexist on a single MediaSession.
        stopPlaybackPipeline()
        sentences = emptyList()
        currentSentenceIndex = 0

        historyRepo.logOpen(fictionId, chapterId)

        _observableState.update {
            it.copy(
                currentFictionId = fictionId,
                currentChapterId = chapterId,
                charOffset = 0,
                isPlaying = true,
                bookTitle = chapter.bookTitle,
                chapterTitle = chapter.title,
                coverUri = chapter.coverUrl,
                // Live streams have unknown duration — surface a
                // 0-length so the UI renders "live" / "—:—" instead
                // of a misleading scrub bar.
                durationEstimateMs = 0L,
                currentSentenceRange = null,
                error = null,
                isLiveAudioChapter = true,
            )
        }
        invalidateState()

        ensureAudioStreamPlayer().run {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    /** Construct the ExoPlayer lazily; subsequent calls reuse the
     *  existing instance. Attaches a listener that bridges ExoPlayer's
     *  playback state into the storyvox [PlaybackState] flow so
     *  MediaSession / notification / lockscreen see the right
     *  isPlaying value when the stream buffers or stalls. */
    private fun ensureAudioStreamPlayer(): androidx.media3.exoplayer.ExoPlayer {
        audioStreamPlayer?.let { return it }
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _observableState.update { it.copy(isPlaying = isPlaying) }
                invalidateState()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _observableState.update {
                    it.copy(
                        isPlaying = false,
                        error = PlaybackError.ChapterFetchFailed(
                            "Audio stream error: ${error.message ?: "unknown"}",
                        ),
                    )
                }
                invalidateState()
            }
            override fun onPlaybackStateChanged(state: Int) {
                val buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                _observableState.update { it.copy(isBuffering = buffering) }
                invalidateState()
            }
        }
        player.addListener(listener)
        audioStreamListener = listener
        audioStreamPlayer = player
        return player
    }

    /** Idempotent teardown — called from [handleStop] / [releaseEngine]
     *  and on every fresh TTS chapter load to make sure the ExoPlayer
     *  doesn't keep streaming audio under a text chapter. */
    private fun stopAudioStreamPlayer() {
        val p = audioStreamPlayer ?: return
        audioStreamListener?.let { p.removeListener(it) }
        audioStreamListener = null
        runCatching { p.stop() }
        runCatching { p.release() }
        audioStreamPlayer = null
    }

    // ----- Issue #189: one-shot recap-aloud TTS -----

    /**
     * Issue #189 — synthesize and play [text] as a one-shot utterance via
     * the active voice. Used by the chapter-recap modal to read the
     * AI-generated recap aloud. Distinct from [loadAndPlay] in that:
     *  - No fiction/chapter binding; doesn't move charOffset.
     *  - Uses a dedicated [recapAudioTrack] + consumer thread; chapter
     *    pipeline state is left untouched (chapter pause is the caller's
     *    job — see ReaderViewModel.toggleRecapAloud).
     *  - When playback ends naturally, [recapPlayback] flips to Idle. The
     *    caller decides whether to auto-resume the fiction (we don't —
     *    the UX is "fiction stays paused so the listener can absorb").
     *
     * Reuses [engineMutex] so the engine isn't entered twice — a recap
     * starting while a chapter generator's JNI call is in flight will
     * wait for it to complete (or for the caller's pause to tear it down).
     *
     * No-op if a voice can't be activated (returns silently; the UI will
     * see [recapPlayback] never leaves Idle and can choose its own
     * fallback). Existing recap playback is stopped before a new one
     * starts.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        stopRecapPipeline()
        if (!ensureVoiceLoaded()) return
        val recapSentences = chunker.chunk(text)
        if (recapSentences.isEmpty()) return
        startRecapPipeline(recapSentences)
    }

    /** Issue #189 — stop an in-flight recap utterance. Idempotent. */
    fun stopSpeaking() {
        stopRecapPipeline()
    }

    /**
     * Issue #290 — point-in-time snapshot of the producer queue +
     * AudioTrack buffer state. Read by the Debug overlay at its 1Hz
     * snapshot cadence; off the hot path entirely.
     *
     * Returns zeros when no pipeline is active (queue/track not bound).
     * `audioBufferMs` is best-effort: AudioTrack's playbackHeadPosition
     * wraps every 2^31 / sampleRate seconds (~24 hours at 24 kHz) — the
     * mask-to-unsigned-int handles single wraps; longer-running pipelines
     * with no rebuild would need an explicit overflow counter (deferred
     * — current chapters rebuild the pipeline on chapter-end well before
     * the wrap window).
     */
    fun bufferTelemetry(): `in`.jphe.storyvox.playback.BufferTelemetry {
        val src = pcmSource
        val track = audioTrack
        val audioBufferMs = if (track != null && track.sampleRate > 0) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val pendingFrames = (totalFramesWritten - head).coerceAtLeast(0)
            pendingFrames * 1000L / track.sampleRate
        } else {
            0L
        }
        return `in`.jphe.storyvox.playback.BufferTelemetry(
            producerQueueDepth = src?.producerQueueDepth() ?: 0,
            producerQueueCapacity = src?.producerQueueCapacity() ?: 0,
            audioBufferMs = audioBufferMs,
        )
    }

    /**
     * Issue #189 — extracted from [loadAndPlay]'s model-load path so
     * [speak] can warm up the active voice without going through the
     * chapter-binding flow. Returns true if a model is loaded and the
     * engine is ready to generate; false if no active voice or load
     * failed (caller renders the failure however it wants — for recap
     * we silently bail and the UI's Read-aloud button stays in its
     * pre-tap state).
     *
     * Skips the load if the same voice is already loaded — the chapter
     * pipeline's [loadedVoiceId] is the canonical signal.
     */
    private suspend fun ensureVoiceLoaded(): Boolean {
        val active = voiceManager.activeVoice.first() ?: return false
        // Already loaded? Nothing to do — the engine is hot.
        if (loadedVoiceId == active.id && activeEngineType != null) return true

        val loadResult: String = withContext(Dispatchers.IO) {
            engineMutex.withLock {
                when (active.engineType) {
                    EngineType.Piper -> {
                        val voiceDir = voiceManager.voiceDirFor(active.id)
                        val onnx = File(voiceDir, "model.onnx").absolutePath
                        val tokens = File(voiceDir, "tokens.txt").absolutePath
                        VoiceEngine.getInstance().loadModel(context, onnx, tokens)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Kokoro -> {
                        val sharedDir = voiceManager.kokoroSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KokoroEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kokoro).speakerId,
                        )
                        // #196 — drive Kokoro's within-sentence comma
                        // pause from the same punctuation-cadence
                        // multiplier we use for between-sentence
                        // silence. 0.2f baseline = engine default; the
                        // multiplier scales it linearly so a 0× user
                        // collapses commas, a 2× user stretches them
                        // to ~0.4f. Field on the engine is read at
                        // config-build time inside loadModel, so set
                        // before loadModel — not after.
                        KokoroEngine.getInstance().setSilenceScale(
                            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
                        )
                        KokoroEngine.getInstance().loadModel(context, onnx, tokens, voicesBin)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Kitten -> {
                        // Issue #119 — Kitten recap path. Tier 3
                        // secondaries are NOT spun up here (recap is a
                        // one-off short read; the primary engine is
                        // sufficient). KittenEngine doesn't expose a
                        // silence-scale knob — it produces cleaner
                        // punctuation cadence at baseline than Kokoro.
                        val sharedDir = voiceManager.kittenSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KittenEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kitten).speakerId,
                        )
                        KittenEngine.getInstance().loadModel(context, onnx, tokens, voicesBin)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Azure -> return@withContext "Error: Azure unsupported in recap"
                }
            }
        }
        if (loadResult != "Success") return false
        activeEngineType = active.engineType
        loadedVoiceId = active.id
        return true
    }

    /**
     * Issue #189 — recap-only producer/consumer pair. Lifted shape from
     * [startPlaybackPipeline] but stripped to the essentials:
     *  - No buffering UI (a 200-word recap is short; underrun is fine).
     *  - No catchup-pause / sleep-timer plumbing.
     *  - On natural end, flips [recapPlayback] back to Idle. No chapter
     *    advance, no position persistence.
     */
    private fun startRecapPipeline(recapSentences: List<Sentence>) {
        val engineType = activeEngineType
        val sampleRate = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            // Issue #119 — Kitten recap sample rate.
            is EngineType.Kitten -> KittenEngine.getInstance().sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val track = createAudioTrack(sampleRate)
        recapAudioTrack = track

        val source = EngineStreamingSource(
            sentences = recapSentences,
            startSentenceIndex = 0,
            engine = activeVoiceEngineHandle(engineType),
            speed = currentSpeed,
            pitch = currentPitch,
            engineMutex = engineMutex,
            punctuationPauseMultiplier = currentPunctuationPauseMultiplier,
            // #486 / #488 — recap utterances honor the same TalkBack pad
            // (a TalkBack user hearing "Here's where you left off…"
            // benefits from the same inter-sentence breathing room).
            extraA11ySilenceMs = cachedA11yExtraSilenceMs,
            queueCapacity = cachedBufferChunks.coerceIn(2, 3000),
            pronunciationDictApply = cachedPronunciationDict::apply,
        )
        recapPcmSource = source
        recapPipelineRunning.set(true)
        _recapPlayback.value = RecapPlaybackState.Speaking

        recapConsumerThread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            var firstSentence = true
            try {
                runCatching { track.play() }
                while (recapPipelineRunning.get()) {
                    val chunk = try {
                        runBlocking { source.nextChunk() }
                    } catch (_: Throwable) {
                        null
                    } ?: break

                    if (firstSentence) {
                        runCatching { track.setVolume(1f) }
                        firstSentence = false
                    }

                    var written = 0
                    while (written < chunk.pcm.size && recapPipelineRunning.get()) {
                        val n = track.write(chunk.pcm, written, chunk.pcm.size - written)
                        if (n < 0) break
                        written += n
                    }
                    var remaining = chunk.trailingSilenceBytes
                    while (remaining > 0 && recapPipelineRunning.get()) {
                        val sz = remaining.coerceAtMost(SILENCE_CHUNK.size)
                        val n = track.write(SILENCE_CHUNK, 0, sz)
                        if (n < 0) break
                        remaining -= n
                    }
                    // Argus Fix B (#79) — see the main consumer loop
                    // above. Recap pipeline mirrors the same headroom
                    // accounting; decrement after the AudioTrack write
                    // so the underrun threshold is checked against
                    // "audio not yet heard," not "audio in the queue."
                    source.decrementHeadroomForChunk(chunk)
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                // Only flip back to Idle if we're still the active recap
                // pipeline — a stop-then-start race could otherwise have
                // the dying old thread reset state for the new one.
                if (recapPipelineRunning.compareAndSet(true, false)) {
                    scope.launch { _recapPlayback.value = RecapPlaybackState.Idle }
                }
            }
        }, "storyvox-recap-out").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopRecapPipeline() {
        recapPipelineRunning.set(false)
        val track = recapAudioTrack
        recapAudioTrack = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        val src = recapPcmSource
        recapPcmSource = null
        if (src != null) runBlocking { src.close() }
        val t = recapConsumerThread
        recapConsumerThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }
        _recapPlayback.value = RecapPlaybackState.Idle
    }

    private companion object {
        /** Fallback when the engine reports a non-positive sample rate (model
         *  not loaded yet). Piper voices are 22050Hz; Kokoro is 24000Hz. */
        const val DEFAULT_SAMPLE_RATE = 22050

        /** Issue #196 — Kokoro's previously-hardcoded silence scale
         *  (0.2f) is the multiplier=1.0 baseline. We linearly scale
         *  with the user's punctuation-cadence multiplier so a 0×
         *  user collapses commas and a 2× user stretches them to
         *  ~0.4f, matching Thalia's recommended Off / Normal / Long
         *  curve from the VoxSherpa knobs research doc. */
        const val KOKORO_SILENCE_SCALE_BASELINE = 0.2f

        /** When buffered audio falls below this, pause AudioTrack and surface
         *  a "Buffering..." UI state.
         *
         *  Calibrated for the Helio P22T worst case (Piper-high "cori" at
         *  0.285× realtime = 3.5× slower than playback). The original 2s
         *  threshold was designed for a near-realtime engine; on a 3.5×
         *  slow producer, the consumer would resume on 4s of headroom,
         *  drain the first chunk, then block on `queue.take()` for ~7s
         *  while the producer finished generating the next 2s sentence —
         *  the audible gap JP reports on the tablet (#79).
         *
         *  Bumping to 7s pauses earlier so the producer has more runway
         *  before the consumer empties the queue; combined with the
         *  raised resume threshold below, the consumer resumes only when
         *  there's enough audio queued to outlast the next generation
         *  cycle. Trade-off: the buffering spinner appears more often
         *  on slow-engine + low-buffer setups. Acceptable — silent
         *  gaps are worse than visible spinners. */
        const val BUFFER_UNDERRUN_THRESHOLD_MS = 7_000L

        /** Hysteresis. Don't resume until we have this much queued or we'll
         *  thrash pause/play on every chunk transition.
         *
         *  Sized to outlast one full producer-generation cycle on the
         *  Helio P22T worst case. With Piper-high at 3.5× realtime, a 2s
         *  sentence takes ~7s of CPU time to render; we want the
         *  consumer to have at least one full cycle of headroom on
         *  resume so it doesn't immediately re-stall. 10s = 7s
         *  generation budget + 3s slack for trailing silence + GC
         *  pauses. Pre-#79 value was 4s, which was sized for near-
         *  realtime engines and produced audible gaps with all
         *  Performance & Buffering toggles ON at buffer=3000. */
        const val BUFFER_RESUME_THRESHOLD_MS = 10_000L

        /** Shared zero-filled buffer the consumer writes from to spool
         *  inter-sentence silence. Sized for one chunk @ 24 kHz mono 16-bit
         *  ≈ 350 ms, which is the longest silence we ever emit; longer
         *  silences chain multiple writes from the same buffer. Static so
         *  every sentence reuses the same allocation. */
        val SILENCE_CHUNK: ByteArray = ByteArray(24_000 * 2 * 350 / 1000)
    }
}
