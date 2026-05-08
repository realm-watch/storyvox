# PCM Cache PR-D Implementation Plan — Streaming-Tee Writer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the cache write side. The producer in `EngineStreamingSource` already generates PCM for each sentence, queues it, and the consumer writes to AudioTrack. PR-D **tees** the producer's output: every byte that goes into the queue ALSO goes into a `PcmAppender` for the (chapter, voice, speed, pitch, chunkerVersion) cache key. On natural end-of-chapter the appender finalizes — the index sidecar lands and the cache becomes "complete" for PR-E to read. On user-initiated stop, voice swap, seek, or speed change, the appender is **abandoned** (partial files deleted) so we never leave a half-written cache pretending to be whole.

**Architecture:** The tee is a thin write-through. The streaming source gains a nullable `PcmAppender` parameter; if non-null, after every successful `engine.generateAudioPCM` it calls `appender.appendSentence(sentence, pcm, trailingSilenceMs)`. The source's lifecycle methods (`close`, restart on `seekToCharOffset`) call `appender.abandon()`. A new finalize entry-point — `finalizeCache()` — is called by the consumer on natural end-of-chapter (the existing `naturalEnd` branch in `EnginePlayer`'s consumer thread). The appender is constructed by `EnginePlayer` (it owns the cache singleton), passed into the source, and the source treats it opaquely.

PR-D also handles the **resume policy** for an in-progress entry that survived a process kill: if `meta.json` exists but `idx.json` doesn't when `EnginePlayer.loadAndPlay` constructs the appender, the existing partial files are wiped (`cache.delete(key)`) BEFORE opening the appender. PR-D's policy is "abandon, restart" — resuming mid-chapter PCM across boots adds complexity (verifying the partial file's integrity, knowing which sentence to continue from) for negligible payoff (the kill-mid-render case is rare; a fresh render takes the same wall time either way).

**Tech stack:** Kotlin, kotlinx.coroutines, JUnit4, Robolectric (where the cache touches `Context.cacheDir`). No new dependencies. Builds on PR-A's `PcmSource` interface and PR-C's `PcmCache` / `PcmAppender`.

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — sections "EngineStreamingSource" ("Tee writes every generated PCM byte to a `PcmAppender`...") and the implementation outline line "PR D — `EngineStreamingSource` tee writer."

**Out of scope (deferred):**

- **Cache hit path.** `loadAndPlay` does NOT consult `PcmCache.isComplete()` to construct a `CacheFileSource` instead — that's PR-E. After PR-D, the cache populates as a side-effect but nothing reads it back; cache-hit replay still goes through the streaming source.
- **WorkManager / RenderScheduler.** Background renders for chapters NOT currently being played (chapter N+2 lookahead, library-add 1-3) are PR-F. PR-D only writes the cache for the chapter you're actively listening to.
- **Settings UI / quota knob.** PR-G. The eviction `evictTo` from PR-C runs at finalize-time using `PcmCacheConfig.quotaBytes()` — default 2 GB.
- **Status icons.** PR-H.
- **Mode C (Full Pre-render).** PR-F adds the toggle; PR-D's tee is unconditional (always on once cache classes are reachable). Spec rationale: the tee write is essentially free during a slow-voice underrun (the producer is the slow path; the appender is microseconds of disk I/O per sentence).

---

## File Structure

### Modified files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt`
  Add `cacheAppender: PcmAppender?` constructor param. After every generated PCM, mirror the chunk into the appender. On `close` / `seekToCharOffset` restart, abandon. Add `finalizeCache()` entry-point for the consumer to call on natural end.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`
  Inject `PcmCache`. Construct a `PcmCacheKey` in `startPlaybackPipeline` from `(currentChapterId, loadedVoiceId, currentSpeed, currentPitch, CHUNKER_VERSION)`. Wipe-and-reopen if a stale partial entry exists. Build the appender, pass into the source. On natural-end branch in the consumer thread, call `source.finalizeCache()` BEFORE the chapter-done coroutine. After finalize, schedule `cache.evictToQuota` on Dispatchers.IO so disk doesn't grow past the user's quota.

### New tests

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceCacheTeeTest.kt`
  Verifies: tee fires per generated sentence; abandon on close; abandon on seek; finalize via the new entry-point produces a complete cache.

---

## Conventions

- All commits use conventional-commit style: `feat`, `fix`, `refactor`, `docs`, `test`. Branch is `dream/<voice>/pcm-cache-pr-d`.
- Run from worktree root.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest`.
- Full app build: `./gradlew :app:assembleDebug`.
- Tablet smoke-test on R83W80CAFZB after PR-D lands: play one chapter to natural end, kill app, look at `${cacheDir}/pcm-cache/` — `.pcm` + `.meta.json` + `.idx.json` triple should be present and the `.pcm` file size should be the full chapter's audio. (Verification gates the PR per memory `feedback_install_test_each_iteration.md`.)
- Selective `git add` per CLAUDE.md.
- **No version bump in this PR.** Orchestrator handles release bundling.

---

## Sub-change sequencing

Three small commits inside the PR so review surface stays narrow:

1. `feat(playback): EngineStreamingSource cache-tee write` — source-side changes only. Tee mirrors generated PCM into a nullable appender; lifecycle hooks abandon. No production caller wires the appender yet, so this commit is a behavioral no-op.
2. `feat(playback): EnginePlayer wires cache appender into streaming source` — EnginePlayer constructs the key, opens the appender via `PcmCache.appender(...)`, finalizes on natural-end. Behavioral change visible only via filesystem (tablet smoke-test required).
3. `test(playback): cache-tee writes match streaming PCM byte-for-byte` — new test class; cross-references PR-C's appender semantics.

---

## PR-D Tasks

### Task D1: Add `cacheAppender` parameter + `finalizeCache()` to `EngineStreamingSource`

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt`

The streaming source is the right place for the tee — it's the only thing that sees every PCM byte the engine generates, and its lifecycle (close, seek-restart) maps cleanly onto cache-abandon semantics. Adding the appender as a constructor param keeps test-friendliness (existing tests pass `cacheAppender = null` and the source behaves as today).

`finalizeCache()` is the only NEW method on the class beyond the existing `PcmSource` interface. Why isn't it on the interface? Because `CacheFileSource` (PR-E) doesn't need it — it reads from an already-finalized cache. Keeping it specific to the streaming source avoids leaking a write-side concept into the interface that PR-E's reader will implement.

- [ ] **Step 1: Add the constructor param.**

In `EngineStreamingSource(...)`, add to the parameter list AFTER `engineMutex` and BEFORE `punctuationPauseMultiplier` (positional ordering keeps the producer-facing knobs grouped):

```kotlin
class EngineStreamingSource(
    private val sentences: List<Sentence>,
    startSentenceIndex: Int,
    private val engine: VoiceEngineHandle,
    private val speed: Float,
    private val pitch: Float,
    private val engineMutex: Mutex,
    /**
     * Optional write-through to the on-disk PCM cache. When non-null, every
     * sentence the producer generates is mirrored into this appender via
     * [PcmAppender.appendSentence]. On [close] (which fires on user pause +
     * pipeline teardown, voice swap, and seek-induced restart) the appender
     * is [PcmAppender.abandon]'d so the partial files don't lie around
     * looking like a complete cache. On natural end-of-chapter (the consumer
     * thread reaches the END_PILL), the consumer calls [finalizeCache]
     * which writes the index sidecar and marks the cache complete for
     * PR-E's `CacheFileSource` to pick up on next play.
     *
     * Null in tests that don't care about the cache, and in pre-cache
     * environments where the feature flag is off (PR-F gates).
     */
    private val cacheAppender: PcmAppender? = null,
    private val punctuationPauseMultiplier: Float = 1f,
    private val queueCapacity: Int = 8,
) : PcmSource {
```

- [ ] **Step 2: Mirror PCM into the appender after each successful generate.**

In `startProducer`, immediately after the existing `runInterruptible { queue.put(Item(chunk)) }` line and the `_bufferHeadroomMs.update { ... }` block, add the tee call. Crucial detail: the tee MUST happen INSIDE the same iteration as the queue put so the byte offsets recorded in the appender index are monotonic with the order the consumer sees them. If we tee after the producer loop completes, a seek that cancels the loop would leave the appender index out of sync with what was actually played.

Find this region:

```kotlin
runInterruptible { queue.put(Item(chunk)) }
_bufferHeadroomMs.update {
    it + pcmDurationMs(pcm.size) + pcmDurationMs(silenceBytes)
}
```

Replace with:

```kotlin
runInterruptible { queue.put(Item(chunk)) }
_bufferHeadroomMs.update {
    it + pcmDurationMs(pcm.size) + pcmDurationMs(silenceBytes)
}
// Tee write — mirror every generated sentence into the on-disk cache.
// Synchronous, on the producer's IO coroutine. The appender's
// FileOutputStream.write+flush is microseconds compared to the
// generateAudioPCM call (Piper-high synthesis is the slow path; disk
// I/O on internal flash is >100 MB/s). Wrapped in runCatching because
// a transient I/O failure (storage full, parent dir wiped) shouldn't
// take down the playback pipeline — the listener still hears the
// sentence; the cache simply won't complete this run.
//
// `pauseMs.toInt()` is the same value already passed to
// silenceBytesFor — recorded here so PR-E's CacheFileSource can
// replay the cadence without recomputing trailingPauseMs.
if (!running.get()) return@launch
cacheAppender?.let { ap ->
    runCatching { ap.appendSentence(s, pcm, pauseMs.toInt()) }
        .onFailure { _cacheTeeErrors.update { it + 1 } }
}
```

- [ ] **Step 3: Add the cache-tee error counter (debug surface).**

Add the StateFlow declaration alongside `_bufferHeadroomMs`:

```kotlin
private val _cacheTeeErrors = MutableStateFlow(0)

/**
 * Count of cache-tee write failures since this source was constructed.
 * Exposed for diagnostic logging — the cache writes are best-effort
 * (a write failure doesn't block playback) so a non-zero value indicates
 * the on-disk cache for THIS chapter run won't be usable. The next play
 * will see `isComplete = false` and re-render fresh.
 *
 * Stays at 0 in normal operation; spikes signal full storage or a
 * permissions regression on the cache directory.
 */
val cacheTeeErrors: StateFlow<Int> = _cacheTeeErrors.asStateFlow()
```

- [ ] **Step 4: Add `finalizeCache()` and abandon hooks.**

After the `close()` body, add:

```kotlin
/**
 * Mark the cache entry for this run complete. Called from the consumer
 * thread's natural-end-of-chapter branch in
 * `EnginePlayer.startPlaybackPipeline`'s consumer loop, AFTER the
 * AudioTrack write loop has drained the last chunk and the END_PILL
 * surfaced from `nextChunk`. Idempotent — calling on a null appender
 * (no cache configured) or after a previous finalize is a no-op.
 *
 * MUST be called BEFORE [close] for the cache write to land. After
 * [close] (or after the abandoning behavior triggered by [seekToCharOffset]
 * restart), the appender is in the closed state and finalize would throw.
 *
 * Wrapped in runCatching for the same reason [appendSentence] is —
 * a finalize-time disk failure (e.g. atomic rename can't write the
 * `.tmp` due to ENOSPC) shouldn't break a chapter that the listener
 * just heard end-to-end. Next play will see incomplete cache + re-render.
 */
fun finalizeCache() {
    cacheAppender?.let { ap ->
        runCatching { ap.finalize() }
            .onFailure { _cacheTeeErrors.update { it + 1 } }
    }
}
```

- [ ] **Step 5: Update `close()` and `seekToCharOffset()` to abandon the appender.**

The streaming source currently treats `close` as "tear everything down". With the tee, we must distinguish:
- "User pause / voice swap / seek / pipeline rebuild" → abandon. The cache for this run is incomplete; next play re-renders.
- "Natural end-of-chapter" → finalize. The cache is complete; next play hits.

`finalizeCache` is called BEFORE `close` from the consumer thread's natural-end branch (Task D2). `close` itself unconditionally abandons whatever's left:

```kotlin
override suspend fun close() {
    running.set(false)
    producerJob.cancel()
    queue.clear()
    queue.offer(END_PILL)
    // Abandon any partial cache. If the consumer already called
    // finalizeCache() on natural end, the appender is closed and
    // abandon() is a no-op. Idempotent.
    cacheAppender?.abandon()
    scope.cancel()
}
```

`seekToCharOffset` cancels the producer mid-flight and restarts at a new sentence index. Two policy choices:
1. Preserve the appender across the seek — accept that byte offsets in the index are wrong (we'd skip sentences between old-cursor and new-cursor).
2. Abandon-and-restart — lose any progress this run made.

We pick **abandon-and-restart**. The whole point of the cache is "complete chapter on disk"; a sparse cache (sentences 0-3, then 12 onwards because the user seeked forward) isn't useful — PR-E's CacheFileSource expects sequential byte offsets. The user can re-listen to get a complete cache.

```kotlin
override suspend fun seekToCharOffset(charOffset: Int) {
    val target = sentences.indexOfLast { it.startChar <= charOffset }
        .takeIf { it >= 0 } ?: 0
    producerJob.cancel()
    queue.clear()
    // Abandon the in-progress cache: a sparse cache is worse than no
    // cache (PR-E's reader assumes sequential byte offsets). The next
    // pipeline rebuild after seek opens a fresh appender.
    //
    // Note: this means a user who seeks within the same chapter
    // restart their cache progress from zero. Acceptable for v0.5.0;
    // a smarter "trim and continue from new cursor" policy can land
    // post-launch if seek-heavy users complain.
    cacheAppender?.abandon()
    producerJob = startProducer(target)
}
```

Note: after seek-induced abandon, the appender is closed; subsequent `appendSentence` calls (from the restarted producer) would throw IllegalStateException. But the consumer's pipeline rebuild on seek (`startPlaybackPipeline` is called from `EnginePlayer.seekToCharOffset` when `isPlaying`) constructs a fresh `EngineStreamingSource` with a fresh appender, so this path is fine — the abandon is for the OLD appender that doesn't outlive the source.

But wait: seekToCharOffset on the SAME source re-uses the producer in-place. So after this abandon, subsequent appends from the restarted producer would throw. We need to NULL OUT the appender after abandoning so the tee no-ops:

Refine — replace the field declaration to allow nullification:

```kotlin
@Volatile private var cacheAppender: PcmAppender? = cacheAppender
```

(In Kotlin you can't reassign a constructor `val` parameter, so we shadow it as a private mutable field.)

Update Step 1's constructor block to receive the appender as a non-`val` parameter, and copy it into the mutable field:

```kotlin
class EngineStreamingSource(
    private val sentences: List<Sentence>,
    startSentenceIndex: Int,
    private val engine: VoiceEngineHandle,
    private val speed: Float,
    private val pitch: Float,
    private val engineMutex: Mutex,
    cacheAppender: PcmAppender? = null,    // not a `val` — we shadow + nullify
    private val punctuationPauseMultiplier: Float = 1f,
    private val queueCapacity: Int = 8,
) : PcmSource {

    @Volatile private var cacheAppender: PcmAppender? = cacheAppender
    // ...rest of class
```

After the abandon in seek and close, set the field to null:

```kotlin
override suspend fun seekToCharOffset(charOffset: Int) {
    val target = sentences.indexOfLast { it.startChar <= charOffset }
        .takeIf { it >= 0 } ?: 0
    producerJob.cancel()
    queue.clear()
    cacheAppender?.abandon()
    cacheAppender = null
    producerJob = startProducer(target)
}

override suspend fun close() {
    running.set(false)
    producerJob.cancel()
    queue.clear()
    queue.offer(END_PILL)
    cacheAppender?.abandon()
    cacheAppender = null
    scope.cancel()
}

fun finalizeCache() {
    cacheAppender?.let { ap ->
        runCatching { ap.finalize() }
            .onFailure { _cacheTeeErrors.update { it + 1 } }
    }
    cacheAppender = null
}
```

- [ ] **Step 6: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run existing tests.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSource*"`
Expected: ALL PASS — pre-existing tests pass `cacheAppender = null` (default param); behavior unchanged.

- [ ] **Step 8: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt
git commit -m "feat(playback): EngineStreamingSource cache-tee write

Producer now mirrors every generated PCM sentence into an optional
PcmAppender. close() and seekToCharOffset() abandon the in-progress
entry; new finalizeCache() entry-point writes the index sidecar on
natural end-of-chapter. Best-effort writes — disk I/O failures
increment cacheTeeErrors but never break playback.

Behavioral no-op for callers passing the default (null) appender.
EnginePlayer wires the appender in the next commit."
```

---

### Task D2: Wire EnginePlayer to construct + finalize the cache appender

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`

EnginePlayer is the right place for cache-key construction because it's the only caller that knows the (chapter, voice, speed, pitch) identity at pipeline-construction time. The cache singleton is injected via Hilt.

Three integration points:
1. **Inject `PcmCache`** — add to the constructor.
2. **Build the key + appender in `startPlaybackPipeline`** — wipe stale partial entries, then open the appender and pass into the source.
3. **Call `finalizeCache()` in the consumer's natural-end branch** — happens BEFORE the chapter-done coroutine launches.
4. **Run eviction after finalize** — top off LRU per spec.

- [ ] **Step 1: Inject `PcmCache`.**

In the EnginePlayer constructor parameter list (alongside `chapterRepo`, `voiceManager`, etc.):

```kotlin
class EnginePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    // ... existing params ...
    private val chapterRepo: ChapterRepository,
    private val voiceManager: VoiceManager,
    private val modeConfig: PlaybackModeConfig,
    private val pcmCache: PcmCache,           // NEW (PR-D)
    // ... existing params ...
)
```

Add the import:

```kotlin
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
```

- [ ] **Step 2: Track current chapter ID for the cache key.**

`startPlaybackPipeline` currently reads `currentSentenceIndex`, `currentSpeed`, `currentPitch` directly from EnginePlayer fields. The chapter ID lives in `_observableState.value.currentChapterId`. Capture it at pipeline-construction time so a mid-pipeline state mutation can't shift the cache key out from under the appender:

In `startPlaybackPipeline`, after the existing `val engineType = activeEngineType` line and before the source construction:

```kotlin
val chapterIdForCache = _observableState.value.currentChapterId
val voiceIdForCache = loadedVoiceId
```

- [ ] **Step 3: Build the cache key + open the appender.**

Replace the existing `EngineStreamingSource(...)` construction block with one that includes the appender:

```kotlin
// Build the cache key for this (chapter, voice, speed, pitch) tuple.
// All four pieces of identity must be known — if any is null we can't
// build a stable key, so we skip the cache write entirely (the source
// gets cacheAppender = null and behaves as pre-PR-D).
val cacheKey: PcmCacheKey? = if (
    chapterIdForCache != null && voiceIdForCache != null
) {
    PcmCacheKey(
        chapterId = chapterIdForCache,
        voiceId = voiceIdForCache,
        speedHundredths = PcmCacheKey.quantize(currentSpeed),
        pitchHundredths = PcmCacheKey.quantize(currentPitch),
        chunkerVersion = CHUNKER_VERSION,
    )
} else null

// Open the appender. If a partial entry exists from a prior killed
// render (meta.json on disk, idx.json absent), wipe it first — PR-D's
// resume policy is "abandon, restart". Resuming mid-chapter PCM across
// boots adds verification complexity (is the .pcm file's tail aligned
// to a sentence boundary? What sentence index do we restart from?) for
// negligible payoff: the kill-mid-render case is rare, and a fresh
// render takes the same wall time as a partial-resume.
//
// If the entry is COMPLETE (idx.json present), don't wipe — PR-E will
// short-circuit before this branch ever runs by constructing a
// CacheFileSource instead. PR-D-only world: a complete entry means
// the user replayed the chapter and the streaming source overwrites
// it with identical bytes (same key → same content). The overwrite
// is wasted work but harmless. PR-E removes this waste.
val appender: PcmAppender? = cacheKey?.let { key ->
    runBlocking {
        if (pcmCache.metaFileFor(key).exists() && !pcmCache.isComplete(key)) {
            // Stale partial — wipe before opening fresh.
            pcmCache.delete(key)
        }
        pcmCache.appender(key, sampleRate = sampleRate)
    }
}

val queueCapacity = cachedBufferChunks.coerceIn(2, 1500)
val source = EngineStreamingSource(
    sentences = sentences,
    startSentenceIndex = currentSentenceIndex,
    engine = activeVoiceEngineHandle(engineType),
    speed = currentSpeed,
    pitch = currentPitch,
    engineMutex = engineMutex,
    cacheAppender = appender,
    punctuationPauseMultiplier = currentPunctuationPauseMultiplier,
    queueCapacity = queueCapacity,
)
```

`runBlocking` here is acceptable: `startPlaybackPipeline` is itself called synchronously from a coroutine (or from suspend functions like `loadAndPlay`), so the blocking wait for `pcmCache.delete` is brief. The delete is a few `File.delete` syscalls on the IO dispatcher — single-digit ms.

If you want to avoid `runBlocking`, an alternative is to make `startPlaybackPipeline` itself `suspend`. That's a bigger refactor (many call sites), so PR-D keeps the runBlocking and PR-G or a follow-up cleanup can lift it.

- [ ] **Step 4: Hook finalize into the consumer's natural-end branch.**

In the consumer thread's `try` body, find the existing `finally` block that handles the natural-end case:

```kotlin
} finally {
    runCatching { track.pause() }
    runCatching { track.flush() }
    runCatching { track.release() }
    // ... isBuffering reset ...
    if (naturalEnd && pipelineRunning.get()) {
        scope.launch {
            sleepTimer.signalChapterEnd()
            handleChapterDone()
        }
    }
}
```

Insert the finalize call BEFORE the `if (naturalEnd ...` branch, so the cache lands before the chapter-done coroutine kicks off (which may rebuild the pipeline for chapter N+1 and overwrite our `streamingSource` reference):

```kotlin
} finally {
    runCatching { track.pause() }
    runCatching { track.flush() }
    runCatching { track.release() }
    if (paused) {
        scope.launch {
            _observableState.update { it.copy(isBuffering = false) }
        }
    }
    // PR-D: finalize the cache on natural end so the index sidecar
    // lands and the cache is complete for next play. Must happen
    // BEFORE the chapter-done coroutine because handleChapterDone
    // calls advanceChapter → loadAndPlay → startPlaybackPipeline,
    // which constructs a NEW source and overwrites the field. We
    // call finalizeCache on the SAME source variable captured by
    // the consumer thread, so the field reassignment doesn't affect us.
    if (naturalEnd && pipelineRunning.get()) {
        runCatching { source.finalizeCache() }
        // Eviction runs AFTER finalize so the just-finalized entry
        // isn't visible to evictTo as an oldest LRU candidate (it's
        // the freshest mtime). Pinned set is the just-finalized
        // basename (currently-playing) — defense in depth in case
        // mtime granularity makes it look "old".
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
```

Note: `source` is the `EngineStreamingSource` local from Step 3, captured by the Thread closure. `cacheKey` is also captured. The Thread runs to completion before `startPlaybackPipeline` is re-entered (the joiner in `stopPlaybackPipeline` blocks for join), so this is race-free.

- [ ] **Step 5: Build full module.**

Run: `./gradlew :core-playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all core-playback tests.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS — existing tests don't construct a real `PcmCache`; Hilt injection in tests uses fakes (`FakePcmCache` implementing the `PcmCache` API) or the real `PcmCache` with a Robolectric-backed `Context`. Verify the test harness in `EnginePlayerTest.kt` (if present) plumbs a fake PcmCache.

- [ ] **Step 7: If `EnginePlayerTest` doesn't yet plumb PcmCache, add a test fake.**

Most likely no full `EnginePlayerTest` exists today (the existing tests are at the source/cache layer). If a test breaks because PcmCache isn't injectable, create:

```kotlin
// core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/FakePcmCache.kt
package `in`.jphe.storyvox.playback.cache

import android.content.Context
import javax.inject.Inject

/**
 * Test fake — minimal stub that satisfies EnginePlayer's PcmCache
 * dependency without a real cacheDir. Tests that don't care about
 * cache behavior pass this; tests that do verify cache state should
 * use the real PcmCache against a Robolectric ApplicationContext.
 */
class FakePcmCache(context: Context, config: PcmCacheConfig)
    : PcmCache(context, config)
```

(If `PcmCache` is final, lift it to `open class` in the same commit — non-breaking change.)

- [ ] **Step 8: Build full app.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — full app compiles with the new dependency.

- [ ] **Step 9: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt
git commit -m "feat(playback): EnginePlayer wires cache appender into streaming source

Pipeline construction now builds a PcmCacheKey from (chapterId,
voiceId, speed, pitch, CHUNKER_VERSION), wipes any stale partial
entry (meta exists, idx absent), and opens a PcmAppender via
PcmCache.appender(...). The streaming source tees every generated
sentence into the appender; on natural end-of-chapter the consumer
calls source.finalizeCache() before the chapter-done coroutine,
landing the index sidecar that marks the cache complete for PR-E
to read on next play.

Eviction (PR-C's evictToQuota) runs after each finalize, pinned to
the just-finalized basename so it's never the LRU victim. Default
quota stays 2 GB (PcmCacheConfig); PR-G surfaces the knob in Settings.

Side effect of pipeline rebuild on speed/pitch/voice change: the
old key's partial cache is abandoned (close() in the source) and a
new key's appender opens. Old (speed=1.0×) cache stays on disk for
LRU; new (speed=1.25×) cache populates fresh.

No reader yet — PR-E adds CacheFileSource to consume what we wrote."
```

---

### Task D3: Cache-tee tests

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceCacheTeeTest.kt`

The test verifies:
1. Tee fires for each generated sentence (count + byte content).
2. `finalizeCache()` lands the index — `PcmCache.isComplete(key)` is true after.
3. `close()` abandons — `isComplete(key)` stays false.
4. `seekToCharOffset` abandons.
5. `cacheTeeErrors` increments on a failing appender.

The test uses Robolectric for `PcmCache`'s `Context.cacheDir` access, same as `PcmCacheTest`.

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineStreamingSourceCacheTeeTest {

    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig

    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        config = PcmCacheConfig(ctx)
        cache = PcmCache(ctx, config)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key = PcmCacheKey(
        chapterId = "ch1",
        voiceId = "cori",
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = CHUNKER_VERSION,
    )

    private val sentences = listOf(
        Sentence(0,  0, 10, "First."),
        Sentence(1, 11, 20, "Second."),
        Sentence(2, 21, 30, "Third."),
    )

    /** Engine that returns a deterministic 100-byte PCM per sentence. */
    private fun fakeEngine() = object : EngineStreamingSource.VoiceEngineHandle {
        override val sampleRate: Int = 22050
        override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? =
            ByteArray(100) { 0x42 }
    }

    @Test
    fun `tee writes one cache entry per sentence then finalize completes the cache`() = runBlocking {
        val appender = cache.appender(key, sampleRate = 22050)
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheAppender = appender,
        )

        // Drain the source — pull every sentence + the END_PILL.
        // This is what the consumer thread does in EnginePlayer; the
        // produced bytes also flow into the tee on the producer side.
        var pulled = 0
        while (true) {
            val c = source.nextChunk() ?: break
            pulled++
            assertEquals(100, c.pcm.size)
        }
        assertEquals(3, pulled)

        // Pre-finalize: pcm + meta exist, idx absent.
        assertTrue(cache.pcmFileFor(key).exists())
        assertTrue(cache.metaFileFor(key).exists())
        assertFalse(cache.isComplete(key))

        source.finalizeCache()

        // Post-finalize: idx lands. Cache complete.
        assertTrue(cache.isComplete(key))
        // Total bytes = 3 sentences × 100 bytes
        assertEquals(300L, cache.pcmFileFor(key).length())

        source.close()
    }

    @Test
    fun `close abandons the in-progress cache (idx never lands)`() = runBlocking {
        val appender = cache.appender(key, sampleRate = 22050)
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheAppender = appender,
        )

        // Pull two of three sentences.
        source.nextChunk()
        source.nextChunk()

        source.close()

        // pcm + meta + idx all gone (abandon deletes the triple).
        assertFalse(cache.pcmFileFor(key).exists())
        assertFalse(cache.metaFileFor(key).exists())
        assertFalse(cache.isComplete(key))
    }

    @Test
    fun `seek abandons cache progress`() = runBlocking {
        val appender = cache.appender(key, sampleRate = 22050)
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheAppender = appender,
        )
        source.nextChunk()    // 1 sentence in flight via tee
        source.seekToCharOffset(20)   // seek into sentence 2

        // Wait briefly for the (cancelled) producer to settle.
        kotlinx.coroutines.delay(100)

        // Cache files for this key should be gone — abandon ran.
        assertFalse(cache.pcmFileFor(key).exists())
        assertFalse(cache.isComplete(key))
        source.close()
    }

    @Test
    fun `null appender means no cache writes (back-compat)`() = runBlocking {
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheAppender = null,    // pre-PR-D behavior
        )
        repeat(3) { source.nextChunk() }
        source.finalizeCache()       // no-op on null

        // No cache files exist for this key.
        assertFalse(cache.pcmFileFor(key).exists())
        assertEquals(0, source.cacheTeeErrors.value)
        source.close()
    }
}
```

- [ ] **Step 1: Create the test file.**
- [ ] **Step 2: Run the test.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSourceCacheTeeTest*"`
Expected: ALL PASS.

- [ ] **Step 3: Run the full module suite.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceCacheTeeTest.kt
git commit -m "test(playback): cache-tee write semantics

Robolectric-backed (uses real PcmCache against ApplicationContext.cacheDir,
same pattern as PcmCacheTest from PR-C). Verifies:
  - Producer tees one entry per sentence; pcm file = concat of sentences
  - finalizeCache lands idx → isComplete = true
  - close abandons → pcm + meta + idx all gone
  - seekToCharOffset abandons (sparse cache prevention)
  - null appender path is a behavioral no-op (pre-PR-D back-compat)"
```

---

### Task D4: Tablet smoke-test PR-D on R83W80CAFZB

**Files:** none — runtime verification.

Tablet verification gates the PR per memory `feedback_install_test_each_iteration.md`. The cache write is observable via filesystem inspection; we don't need PR-E (cache hit replay) to confirm PR-D worked.

- [ ] **Step 1: Claim tablet lock #47.**

Run: `gh task update 47 --owner <voice>` (TaskUpdate tool).
Expected: lock owned.

- [ ] **Step 2: Build + install.**

```bash
./gradlew :app:assembleDebug
adb -s R83W80CAFZB install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Foreground app, play Sky Pride Ch.1 to natural end (or a short test chapter).**

Sky Pride Ch.1 is ~26 min on Piper-high. For tablet smoke-test economy, prefer a short chapter (find one ≤ 5 min). Listen / wait for natural end-of-chapter.

- [ ] **Step 4: Verify cache files landed.**

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls -la cache/pcm-cache/
```

Expected: `<sha>.pcm`, `<sha>.meta.json`, `<sha>.idx.json` triple present.
The `.pcm` size should equal the chapter's full PCM duration × 22050 × 2 bytes (Piper) — for a 5-min chapter ~13 MB.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  cat cache/pcm-cache/<sha>.idx.json | jq .sentenceCount
```

Expected: matches the sentence count (the chunker chunked into).

- [ ] **Step 5: Pause/seek/voice-swap and verify abandon.**

- Restart the chapter, play 30 s, then pause. Note: pause via the user UI calls `stopPlaybackPipeline` which closes the source and abandons the cache. After pause the .pcm file for the current key should be GONE.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls cache/pcm-cache/
```

Expected: only the previously-finalized (different key — different chapter/voice) entries remain. The current key's triple is gone.

- Resume playback. The pipeline rebuilds, opens a fresh appender, the .pcm file appears again.

- [ ] **Step 6: Quota / eviction sanity (optional).**

If you've played 5+ chapters, total cache size should be bounded by `PcmCacheConfig.DEFAULT_QUOTA_BYTES` = 2 GB:

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  du -sb cache/pcm-cache/
```

Expected: total ≤ 2 GB. (Hard to hit in a smoke test; mostly a sanity check that eviction code wasn't bypassed.)

- [ ] **Step 7: Release tablet lock #47.**

- [ ] **Step 8: Push branch.**

```bash
git push -u origin dream/<voice>/pcm-cache-pr-d
```

---

### Task D5: Open PR-D

**Files:** none — open PR via gh.

- [ ] **Step 1: Open PR.**

```bash
gh pr create --base main --head dream/<voice>/pcm-cache-pr-d \
  --title "feat(playback): PCM cache streaming-tee writer (PR-D)" \
  --body "$(cat <<'EOF'
## Summary

PR-D of the chapter PCM cache spec'd in
`docs/superpowers/specs/2026-05-07-pcm-cache-design.md`. Wires the
**cache write side**: every PCM byte the producer in
`EngineStreamingSource` generates is mirrored into a `PcmAppender`
keyed on (chapter, voice, speed, pitch, chunkerVersion). On natural
end-of-chapter the appender finalizes — the index sidecar lands and
the cache becomes 'complete' for PR-E's `CacheFileSource` to pick
up on next play. On user pause / seek / voice swap / speed change
the appender abandons (partial files deleted) so we never have a
half-written cache pretending to be whole.

## What's NOT in this PR

- **Cache hit playback path.** `EnginePlayer.loadAndPlay` does NOT
  yet check `PcmCache.isComplete()` to swap in a `CacheFileSource`.
  That's PR-E. After PR-D, replay still re-renders (writing the
  same bytes over the cache) — wasted work, but harmless. PR-E
  short-circuits.
- **WorkManager / RenderScheduler.** Background renders for chapters
  NOT currently being played (chapter N+2 lookahead, library-add 1-3)
  are PR-F.
- **Settings UI for cache size + Mode C.** PR-G.
- **Status icons.** PR-H.

## Implementation notes

- The tee is synchronous on the producer's IO coroutine (
  generateAudioPCM is the slow path; disk write is microseconds).
- Disk failures increment `cacheTeeErrors` but never break playback —
  worst case the cache for THIS run won't complete and the next play
  re-renders.
- Resume policy on partial entries (meta exists, idx absent from a
  killed prior render) is **abandon-and-restart**. Resuming mid-chapter
  PCM across boots adds verification complexity for negligible payoff.
- Eviction (PR-C's `evictToQuota`) runs after each successful finalize,
  pinned to the just-finalized basename so it's never the LRU victim.

## Test plan

- [x] Pure-JVM + Robolectric tests:
  - cache-tee writes match the engine's PCM byte-for-byte
  - finalize lands idx → isComplete = true
  - close abandons → all three files gone
  - seek abandons (sparse cache prevention)
  - null appender = behavioral no-op (back-compat)
- [x] R83W80CAFZB smoke test:
  - play short chapter to natural end → cache triple present, .pcm
    size matches expected chapter PCM bytes
  - pause mid-chapter → current key's triple gone (abandon ran)
  - resume → fresh appender opens, files reappear
- [x] `./gradlew :core-playback:testDebugUnitTest` green
- [x] `./gradlew :app:assembleDebug` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Capture PR number and request Copilot review** (per memory `feedback_copilot_review_required.md`):

```bash
PR_NUM=$(gh pr view --json number --jq .number)
gh api -X POST repos/jphein/storyvox/pulls/$PR_NUM/requested_reviewers \
  -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
```

- [ ] **Step 3: Capped wait playbook (per memory).**
- 5 min: poll `gh pr view $PR_NUM --json reviews,reviewRequests,statusCheckRollup`.
- If silent drop, re-request once.
- 5 min more.
- 15 min hard cap: ship on clean CI.

- [ ] **Step 4: Address substantive Copilot findings in fixup commits, push, wait for CI green, squash-merge.**

- [ ] **Step 5: Post-merge cleanup.**

```bash
git push origin --delete dream/<voice>/pcm-cache-pr-d
git checkout main && git pull
```

(per CLAUDE.md "always return to main")

---

## Open questions for JP

1. **Synchronous tee write on producer thread.** The `appender.appendSentence` is a `FileOutputStream.write` + `flush` per sentence. On internal flash (~100 MB/s seq) a 100-150 KB Piper sentence is sub-millisecond — well below the producer's per-sentence wall time. But on slow eMMC (Tab A7 Lite has UFS 2.0 / not eMMC, so this is fine; some sub-tablets do have eMMC), a flush could occasionally take 50+ ms. Acceptable, or should we batch the flush (write without flush per sentence, flush every N sentences)? Spec doesn't specify. **Recommendation: ship synchronous; only revisit if profiling shows the write is hot.**

2. **Resume policy.** Plan picks "abandon, restart" for partial entries. Spec leaves this open ("PR-D's resume policy will be 'abandon, restart' since the byte offsets we'd have written aren't on disk" — actually spec line 410 in `PcmAppender.kt` doc states this; consistent). **Confirmed.**

3. **Cache wipe order in `loadAndPlay`.** We `runBlocking { pcmCache.delete(key) }` if a stale partial exists. This blocks pipeline construction by ~1-5 ms. Acceptable, or should we lift `startPlaybackPipeline` to suspend? The latter is a bigger refactor. **Recommendation: ship runBlocking; lift to suspend in a follow-up cleanup if it becomes a hot path.**

4. **Speed/pitch quantization rounding.** `PcmCacheKey.quantize(currentSpeed)` uses `Math.round` (half-up). 1.025× → 103, 1.024× → 102. Cache hit/miss boundary depends on this. **Acceptable per spec (line 380): "Speed quantization to 0.05× (5 hundredths)..."** Minor: spec says quantize to 0.05× steps; we quantize to 0.01× steps. Stricter than spec — more cache files, but fewer false hits. **If JP wants the looser 0.05× quantization, we'd round speed × 100 to nearest 5 instead of nearest 1. Punting to JP.**

5. **Should the tee write be gated by Mode C?** No — Mode C (Full Pre-render) is for BACKGROUND prefetch (PR-F). PR-D's tee is "the chapter you're actively playing populates its own cache as a free side-effect of synthesis you're already doing". Always-on. PR-G's quota knob is the user-visible disable (set to 100 MB to effectively cap the cache).

---

## Self-review

**Spec coverage check (PR-D scope from spec line 405):**
- ✓ EngineStreamingSource tee write → Task D1
- ✓ "side-effect of playback (each generated PCM byte appended to cache file)" → Task D1 step 2 (after every successful generate)
- ✓ "on natural-end finalizes" → Task D2 step 4 (consumer thread natural-end branch)
- ✓ Mutual exclusion contract trusted to caller (PR-D doesn't add mutex; engineMutex already serializes generate calls)
- ✓ Cache populates organically from playback → confirmed via tablet smoke test in Task D4

**Spec deltas / decisions:**
- **Resume policy = abandon-and-restart.** Spec leaves room for "resume mid-chapter" (`PcmAppender.kt` doc says "PR-D's resume policy will be 'abandon, restart'"); we confirm and document why.
- **Tee write is synchronous + best-effort.** Disk failures increment `cacheTeeErrors` but don't fail playback.
- **`finalizeCache()` is on `EngineStreamingSource`, not `PcmSource` interface.** `CacheFileSource` (PR-E) doesn't need it.
- **Stale partial wipe lives in EnginePlayer.startPlaybackPipeline, not PcmCache.appender.** Keeps `PcmCache.appender` policy-free; the "wipe vs resume" decision is PR-D's, not PR-C's.

**Placeholder scan:** None. Every Kotlin block compiles in context.

**Type consistency:** `EngineStreamingSource` constructor parameter order matches between declaration, `EnginePlayer.startPlaybackPipeline` construction, and the test class. `PcmCacheKey` field order matches PR-C's ship.

**Risks deferred to follow-up PRs:**
- Cache hit replay (PR-E): right now we tee-write on every play, even if a complete cache already exists. Wasteful but harmless. PR-E short-circuits.
- Background renders (PR-F): PR-D only writes the actively-played chapter; chapter N+2 lookahead and library-add-1-3 wait for PR-F.
- Mode C UI (PR-G): no Settings knob in this PR.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-d.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute D1-D5 inline. PR-open is the JP-visible boundary; everything before that stays in-session.
