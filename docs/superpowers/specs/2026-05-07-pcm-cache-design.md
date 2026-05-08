# Chapter PCM Cache — Design Spec

**Author:** Aurelia (perf lane)
**Date:** 2026-05-07
**Status:** Draft, awaiting JP review
**Branch:** `dream/aurelia/tts-chunk-gap`

## Problem

On Galaxy Tab A7 Lite (Helio P22T, 3 GB RAM) running Piper-high "cori",
TTS synthesis runs at **0.285× realtime** — the producer falls behind
playback by ~700 ms per second of audio, indefinitely. Measured baseline:

| metric | value |
|---|---|
| chunks captured | 20 (Sky Pride Ch.1, sentences 8-28) |
| audio synthesized | 45520 ms |
| wall time spent generating | 159820 ms |
| realtime factor | **0.285×** |
| inter-chunk audible gap (median) | **8021 ms** |
| inter-chunk audible gap (max) | **19601 ms** |

The current streaming pipeline (producer/consumer with 8-slot prefetch
queue, dedicated URGENT_AUDIO consumer thread, AudioTrack at minBufferSize)
has a one-time grace at chapter start (queue + AudioTrack hardware buffer
≈ 10-30 s of cushion), then steady-state underrun is unavoidable. No
in-pipeline tuning closes the gap when synthesis < playback rate.

JP's success criterion: **high-quality voices play smoothly on slow devices**.

## Solution: render to disk, stream from disk

Move synthesis off the playback hot path. Render each chapter's PCM to a
cache file with a sentence-offset sidecar index. Playback streams from
the cache file rather than from live engine output. When the cache exists
and is complete, playback is gapless on any device because no inference
runs in the playback thread.

Streaming synthesis remains as fallback for first-ever chapter plays
(cache miss); the streaming path adds a "buffering" UI state instead of
emitting silence on underrun.

## UX shape

| user action | behavior |
|---|---|
| First-ever chapter play (no cache) | Streams via current pipeline, with **pause-buffer-resume** when head-room < 2 s. Cache renders in background as a side-effect of playback (each generated PCM byte appended to cache file). |
| Replay of cached chapter | Instant. Plays from cache file, gapless. |
| Adds fiction (FAB → URL paste, Library "+", browse → add) | Schedules render of chapters 1-3 in background. |
| Finishes a chapter via natural-end | Schedules render of chapter N+2 (N+1 is usually already rendered or in flight). |
| Voice swap mid-chapter | Cache is voice-keyed; current chapter's old-voice cache stays on disk (LRU-evictable). Re-render with new voice in background. Playback streams via fallback path until new cache catches up. |
| Speed/pitch change | Same as voice swap — cache key includes speed and pitch quantized to 2 decimals. |
| Seek backward into rendered region | Instant seek (file offset lookup via index). |
| Seek forward past rendered watermark | Streaming fallback resumes from the seek target until cache catches up. |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        EnginePlayer                              │
│  (existing SimpleBasePlayer surface, unchanged externally)       │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ loadAndPlay(fictionId, chapterId, charOffset):           │   │
│  │   key = PcmCacheKey(chapterId, voiceId, speed, pitch,    │   │
│  │                     chunkerVersion)                       │   │
│  │   if pcmCache.isComplete(key):                           │   │
│  │     source = CacheFileSource(pcmCache.fileFor(key),      │   │
│  │                              pcmCache.indexFor(key))     │   │
│  │   else:                                                   │   │
│  │     source = EngineStreamingSource(engine, sentences,    │   │
│  │                                    pcmCache.appender(key))│   │
│  │     scheduler.scheduleRender(key, fictionId, chapterId)  │   │
│  │   pipeline = PlaybackPipeline(source, audioTrack,        │   │
│  │                               sentenceTracker)            │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘

         ▲                        ▲                       ▲
         │                        │                       │
   ┌─────┴────┐          ┌────────┴──────┐         ┌──────┴──────┐
   │PcmSource │          │   PcmCache    │         │  Render-    │
   │interface │          │ (LRU + quota) │         │ Scheduler   │
   └──────────┘          └───────────────┘         └─────────────┘
        ▲
   ┌────┴──────────────────────────┐
   │                                │
┌──┴───────────────┐    ┌───────────┴──────────────────┐
│ CacheFileSource  │    │   EngineStreamingSource      │
│ (mmap PCM file,  │    │  (current producer/consumer  │
│  read sequentially│   │   pipeline + cache appender   │
│  from offset)    │    │   tee + buffer-low signal)   │
└──────────────────┘    └──────────────────────────────┘
```

## Components

### `PcmCacheKey`

```kotlin
data class PcmCacheKey(
    val chapterId: String,
    val voiceId: String,
    val speedHundredths: Int,   // currentSpeed * 100, rounded
    val pitchHundredths: Int,
    val chunkerVersion: Int,    // bump in SentenceChunker if logic changes
) {
    fun fileBaseName(): String  // SHA-256 of toString()
}
```

**Why all five fields:** the audio depends on every one. Same chapter at
1.0× and 1.2× speed are different audio. Same voice with pitch shift = same.
Chunker version invalidates if sentence boundaries change between releases
(otherwise the sidecar index is wrong).

### `PcmCache`

Filesystem-backed cache with LRU eviction.

```kotlin
@Singleton
class PcmCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheConfig: PcmCacheConfig,
) {
    suspend fun isComplete(key: PcmCacheKey): Boolean
    fun fileFor(key: PcmCacheKey): File          // PCM data
    fun indexFor(key: PcmCacheKey): File         // sidecar JSON
    fun appender(key: PcmCacheKey): PcmAppender  // streaming-fallback writer

    suspend fun evictTo(quotaBytes: Long, pinnedKeys: Set<PcmCacheKey>)
    suspend fun totalSizeBytes(): Long
    suspend fun delete(key: PcmCacheKey)
    suspend fun deleteAllForChapter(chapterId: String)  // chapter text changed
}

class PcmAppender(file: File, indexFile: File) {
    fun appendSentence(sentence: Sentence, pcm: ByteArray)
    fun finalize()  // writes the index file, marks cache complete
    fun abandon()   // deletes partial files (e.g. on user-pause-during-stream)
}
```

**Files on disk:**
- `${cacheDir}/pcm-cache/<sha>.pcm` — raw 16-bit signed mono PCM at engine sample rate
- `${cacheDir}/pcm-cache/<sha>.idx.json` — sentence offset index, written ONLY when render completes (presence = "cache is complete")
- `${cacheDir}/pcm-cache/<sha>.meta.json` — voice id, sample rate, sentence count, total bytes, created timestamp; written at start of render so an in-progress render is identifiable

Index format (sidecar JSON):
```json
{
  "sample_rate": 22050,
  "sentence_count": 87,
  "total_bytes": 71098368,
  "sentences": [
    {"i": 0, "start": 0,   "end": 142, "byte_offset": 0,         "byte_len": 824032},
    {"i": 1, "start": 142, "end": 287, "byte_offset": 824032,    "byte_len": 656128},
    ...
  ]
}
```

`start`/`end` are character offsets into the chapter's plaintext (so
`SentenceTracker` can keep working unchanged). `byte_offset`/`byte_len`
locate the sentence's PCM in the .pcm file.

**Quota:** default 2 GB user-configurable in Settings:
- 500 MB (light)
- 2 GB (default — ~30 chapters of Piper-high Sky Pride)
- 5 GB
- Unlimited

LRU based on file mtime updated on every play (touch the .pcm file when
opening). Pinned: currently-playing chapter + next chapter in sequence
(if known). Eviction runs at scheduler enqueue time and after each render
completes.

### `PcmCacheConfig`

DataStore-backed user preference, `quotaBytes`, default 2 GB. Read on
demand from Settings, no DI cycles.

### `PcmSource` (interface)

```kotlin
sealed interface PcmSource {
    val sampleRate: Int
    val sentenceCount: Int

    /** Yields ranges of PCM with their sentence index. Suspending so
     *  the streaming impl can block on engine generation. The cached
     *  impl returns immediately from mmap. */
    suspend fun nextChunk(consumed: Long): PcmChunk?
    suspend fun seekToCharOffset(offset: Int): PcmChunk?
    suspend fun close()
}

data class PcmChunk(
    val sentenceIndex: Int,
    val sentenceRange: SentenceRange,
    val pcm: ByteBuffer,         // direct buffer, mmap'd or fresh
    val trailingSilenceMs: Int,
)
```

### `CacheFileSource`

mmap's the .pcm file via `RandomAccessFile.channel.map(READ_ONLY, 0, len)`,
reads sequentially with optional seek. Loads the index JSON once at construction.
No engine calls — playback is purely I/O bound, which on internal flash is
trivially fast (>100 MB/s sequential read).

`trailingSilenceMs` is read from the index (computed by `trailingPauseMs()`
at render time).

### `EngineStreamingSource`

The current producer/consumer pipeline, refactored to expose `nextChunk()`.
Internally still has the IO-coroutine producer + LinkedBlockingQueue +
URGENT_AUDIO consumer thread. **Tee writes** every generated PCM byte
to a `PcmAppender` for the same key; if synthesis completes the chapter,
the appender finalizes and the cache becomes available for replay.

Adds a `bufferLowSignal: StateFlow<Boolean>` that flips true when the
queue head-room (queued PCM duration) falls below 2 s. The `PlaybackPipeline`
collects this and pauses `track.play()` while true, surfacing a "buffering"
state in `PlaybackState`. When head-room recovers, resume.

### `RenderScheduler`

Wraps WorkManager. Single in-flight render at a time (engine is a singleton).

```kotlin
@Singleton
class PcmRenderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleRender(key: PcmCacheKey, fictionId: String, chapterId: String)
    fun cancel(chapterId: String)
}
```

Uses `OneTimeWorkRequest` with unique-name `pcm-render-<chapterId>`,
ExistingWorkPolicy.KEEP. Constraints: `requiresBatteryNotLow=true`,
`requiresStorageNotLow=true`. The worker:

1. Re-reads the chapter text from `ChapterRepository`.
2. Re-reads the active voice from `VoiceManager` (voice may have changed
   between schedule and run — recompute the cache key).
3. Acquires `engineMutex` (the existing one in EnginePlayer, hoisted to a
   `@Singleton EngineMutex` provider).
4. Runs the same generation loop as `EnginePlayer.startPlaybackPipeline()`'s
   producer, but writes to a `PcmAppender` instead of a queue.
5. On completion, finalizes the index and runs LRU eviction.
6. On cancellation (chapter no longer wanted, or playback abandons cache),
   `appender.abandon()` to remove partial files.

**Trigger points:**

- `EnginePlayer.loadAndPlay` on cache miss: do NOT schedule a separate worker — the streaming source's `PcmAppender` tee-write IS the render for the currently-playing chapter. The worker path is only used for chapters that are NOT currently being played.
- `EnginePlayer.handleChapterDone`: schedule a worker render of N+2 (N+1 should already be cached, having been the target of the previous N's chapter-done trigger).
- `FictionRepository.addToLibrary`: schedule worker renders of chapters 1-3.
- `VoiceManager.setActive(newVoice)` while a chapter is loaded: the streaming source's appender abandons partial files for the old key (incomplete render). Schedule a worker render for the new (chapter, voice) key only if the chapter isn't currently re-loading via `loadAndPlay`.

**Mutual-exclusion contract:** at most one process writes to a given cache key at a time — either the foreground streaming source's appender, OR the background `ChapterRenderJob`. The render scheduler checks for an existing complete or in-flight key before enqueueing; the streaming source's `loadAndPlay` cancels any matching in-flight worker before opening its own appender (using the unique work name).

### `PlaybackPipeline`

Renamed extraction of the consumer half of `EnginePlayer.startPlaybackPipeline`,
now decoupled from the producer:

```kotlin
class PlaybackPipeline(
    private val source: PcmSource,
    private val audioTrack: AudioTrack,
    private val sentenceTracker: SentenceTrackerSink,
    private val volumeRamp: TtsVolumeRamp,
    private val bufferLowSignal: StateFlow<Boolean>,  // from EngineStreamingSource only
)
```

Loops on `source.nextChunk()`, writes to AudioTrack as today. When
`bufferLowSignal` flips true, calls `audioTrack.pause()` + emits a
`PlaybackState.buffering=true`. When the source has buffered at least
4 s of audio again, calls `audioTrack.play()` + emits `buffering=false`.
For `CacheFileSource` the signal is always false (no underrun possible).

## Integration with existing features

### SentenceTracker / sentence highlighting

The sidecar index gives `(sentenceIndex, charOffset)` per sentence. The
existing `SentenceTracker` consumes `SentenceRange` events; we still emit
those when the consumer crosses a sentence boundary in the PCM stream.
`PcmSource.nextChunk` returns a `PcmChunk` with `sentenceRange` —
unchanged contract.

### Sleep timer

`TtsVolumeRamp` operates on `AudioTrack.setVolume`, source-agnostic. No change.
`SleepTimer.signalChapterEnd` fires from `PlaybackPipeline` when source
is exhausted. No change.

### Voice swap mid-chapter

`EnginePlayer.observeActiveVoice` already handles this. New behavior:
old `PlaybackPipeline` is torn down; new `loadAndPlay(fictionId,
chapterId, charOffset)` runs. New cache key = miss → `EngineStreamingSource`
with appender for new voice. `RenderScheduler.scheduleRender` queues a
background render of the same chapter+new-voice key.

### Speed/pitch change

Currently rebuilds the pipeline (`startPlaybackPipeline()`). Same
behavior: cache key changes → likely miss → streaming fallback +
schedule render. Old-speed cache stays on disk (LRU).

### Seek

`PcmSource.seekToCharOffset(offset)` returns a `PcmChunk` for the sentence
containing that offset.

- `CacheFileSource`: index lookup, `RandomAccessFile.seek(byteOffset)`.
- `EngineStreamingSource`: cancels in-flight generation, restarts producer
  from the new sentence index. The streaming appender's progress is
  preserved if the seek lands within already-rendered region; otherwise
  `appender.abandon()` and a fresh render scheduled.

### MediaSession / Wear / Auto

`EnginePlayer` keeps its `SimpleBasePlayer` API. `getState()` still maps
internal state to `Player.State`. New `buffering` state maps to
`Player.STATE_BUFFERING`. No change to media-route consumers.

### Add-fiction flows / browse / library

`FictionRepository.addToLibrary` (existing or new method) gains a
"after add, schedule renders for chapters 1-3" hook. This is opt-in via
a setting (default on for sequential listening). Cancelled if user removes
fiction from library.

## Storage policy

- Default quota: **2 GB**. Configurable in Settings → Playback → "Audio cache size":
  - 500 MB (light)
  - 2 GB (default)
  - 5 GB
  - Unlimited
- LRU eviction by file mtime (touched on every play of the cached chapter).
- Pinned (never evicted): currently-playing chapter + next chapter in sequence.
- Eviction runs:
  - Before each render (must fit the new file in budget).
  - After each render completes (top off LRU to budget).
  - When user lowers the quota in Settings.
- Disk-full fallback: if `requiresStorageNotLow` is violated mid-render,
  WorkManager halts the worker; the appender's partial files stay on disk
  for resume next time. (`ChapterRenderJob` uses `setForeground` for long
  renders to avoid OS-doze interruption.)

User-facing UI:

- **Settings → Playback → Audio cache**:
  - Currently used: `1.4 GB / 2 GB`
  - Quota selector (radio buttons)
  - "Clear cache" button (deletes all `.pcm` + `.idx.json` + `.meta.json`)

## Migration / rollout

- Zero DB migration. State is filesystem-only.
- No behavior change on first install — cache populates as side-effect of streaming on first play. Streaming itself improves (pause-buffer-resume vs silence) immediately.
- Feature flag: marker file `pcm-cache-enabled` in `filesDir`. Off by default until v0.5.0; on by default after. Reuses the existing marker-file pattern (AudioTrack.Builder, ChunkGapLogger).

## Risks and open questions

| risk | mitigation |
|---|---|
| **Storage runaway.** A 26-min Piper-high chapter is ~68 MB. A binger of long progression fantasy could hit 2 GB in a week. | LRU + quota with sensible default; user-visible Settings UI. |
| **Render falls behind in WorkManager.** OS may pause/throttle background work; a chapter render at 0.285× rtf takes 90+ minutes wall — not run-once-and-done. | `setForeground` for the worker so it survives doze. Battery-not-low constraint avoids drain at low charge. Single-in-flight + retry on transient failure. |
| **Engine singleton + WorkManager render = two threads in JNI.** EnginePlayer already serializes via `engineMutex`; promoting the mutex to a process-wide singleton (provided by Hilt) lets the worker share it. Race is the same shape as the existing voice-reload race. | Hoist `engineMutex` to `@Singleton` `EngineMutex` provider; both `EnginePlayer` and `ChapterRenderJob` use the same instance. |
| **Cache key explosion with speed knob.** Users tweak speed often. Each speed creates a different cache file. | Speed quantization to 0.05× (5 hundredths) so 1.0× and 1.02× share a cache file. Hopefully users settle on 1.0/1.25/1.5/1.75/2.0×, all distinct keys but a small finite set. |
| **First-play UX still bad on slow voice.** First chapter ever played still streams with buffering pauses while cache renders alongside. | Pre-render trigger on add-to-library (chapters 1-3 in background). For most users, "add fiction → walk away → come back" lands on a populated cache. |
| **Sentence index drift.** If `SentenceChunker` boundaries change between app versions, the index is wrong. | `chunkerVersion: Int` constant in `SentenceChunker`, bumped when boundaries change; included in cache key so old caches invalidate. |
| **Cache replay vs sentence-highlight motion glide (Lumen's PR #63).** The glide depends on real-time sentence transitions. Cache file streams audio fast — does the consumer pace via `track.write()` blocking, or freerun and out-pace UI? | Cache consumer paces via `track.write()` exactly like streaming consumer. AudioTrack hardware buffer (~2-3 s) regulates the rate. UI updates fire at sentence transitions, same code path. |

## Out of scope

- Re-encoding to compressed format (Ogg/Opus). Considered: would cut storage 10×, but adds decode CPU on playback (small but non-zero on the same slow devices we're trying to help) and risks reintroducing the Samsung speech-DSP fuzz path. Defer until storage proves to be a real-world problem.
- Sharing cache across devices (cloud-sync of pre-rendered audio). Privacy and bandwidth nightmare.
- Sharing cache across users (if multiple users use the app). Out of scope: app is single-user.
- Per-fiction cache pin ("always keep this fiction cached"). Nice-to-have; defer.
- Pre-render whole library at install. UX cost (bad first-run) outweighs benefit. The post-add 1-3 chapter pre-render covers the most common case.
- Showing per-chapter cache status icons in the chapter list. Polish for v0.5.x; not required for this PR.

## Implementation outline (for writing-plans skill follow-up)

Suggested PR shape (not commitments, the implementation plan will refine):

1. **PR A — `PcmSource` extraction.** Refactor `EnginePlayer.startPlaybackPipeline` to extract a `PcmSource` interface and the existing `EngineStreamingSource` impl. Behavioral no-op. Tests for `PcmSource` semantics. Lays groundwork.

2. **PR B — `pause-buffer-resume`.** Add `bufferLowSignal` to `EngineStreamingSource`. Pipeline pauses `audioTrack.play()` instead of emitting silence on underrun. PlaybackState gains `buffering: Boolean`. UI gains "buffering…" state in PlaybackSheet (Lumen's lane). **This alone is shippable as v0.4.27 and improves the gap UX immediately**; subsequent PRs add the cache.

3. **PR C — `PcmCache` + `PcmCacheKey` + filesystem layout.** Just the storage layer + tests. No integration yet.

4. **PR D — `EngineStreamingSource` tee writer.** Streaming source writes generated PCM to `PcmAppender` as a side-effect; on natural-end finalizes. Cache populates organically from playback.

5. **PR E — `CacheFileSource` + cache hit path.** `EnginePlayer.loadAndPlay` consults `PcmCache.isComplete`; mmap + play if hit. Sentence index drives sentence ranges.

6. **PR F — `PcmRenderScheduler` + `ChapterRenderJob`.** Background WorkManager render. Wired to `loadAndPlay` cache miss + `handleChapterDone` (N+2) + `addToLibrary` (1-3).

7. **PR G — Settings UI for quota + LRU eviction.** User-facing Settings entry, eviction runs at scheduler enqueue.

8. **PR H — Polish.** Per-chapter cache status icon, "Clear cache" affordance.

Total estimated work: 2-3 weeks of single-engineer time, but each PR is independently shippable and individually small. PR B alone can ship in this branch's lifetime to address JP's immediate complaint with a smaller win.

## Definition of done

- A chapter played to completion on Piper-high Tab A7 Lite leaves a complete `.pcm` + `.idx.json` cache.
- Replaying that chapter from any starting offset is gapless (median inter-chunk gap ≤ 350 ms — i.e. at most the deliberate cadence).
- First-ever play streams with `buffering` UI state when the queue runs dry, never silence.
- `EnginePlayer` MediaSession surface unchanged (Wear / Auto / Bluetooth still see `Player.STATE_*` consistently).
- Cache total size respects user quota; LRU evicts cleanly.
- Voice swap, speed change, seek, and chapter advance all work and don't corrupt cache.
- All existing core-playback tests still pass; new tests cover `PcmCache`, `PcmCacheKey` hashing, `CacheFileSource` seek, eviction.
