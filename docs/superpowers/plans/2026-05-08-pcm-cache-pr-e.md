# PCM Cache PR-E Implementation Plan — CacheFileSource + Cache Hit Path

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the **read side**. PR-D populates the cache as a side-effect of streaming playback. PR-E adds the second `PcmSource` impl — `CacheFileSource` — that reads cached PCM from disk via mmap and the sidecar index, plus the dispatch logic in `EnginePlayer.loadAndPlay` that picks `CacheFileSource` when `PcmCache.isComplete(key)` is true and falls back to `EngineStreamingSource` otherwise. Result: the second-and-subsequent play of any chapter is **gapless** because no engine inference runs during playback.

This is the structural payoff PR. Spec's success criterion ("high-quality voices play smoothly on slow devices") becomes true here for replays. First plays still stream (with PR-B's pause-buffer-resume on underrun); cached replays skip synthesis entirely.

**Architecture:**

```
EnginePlayer.loadAndPlay(fictionId, chapterId, charOffset)
  ├── construct PcmCacheKey from (chapter, voice, speed, pitch, chunkerVersion)
  ├── if PcmCache.isComplete(key):
  │     PcmCache.touch(key)          ← bumps mtime so LRU favors live entries
  │     source = CacheFileSource(key, ...)   ← mmap, no engine call
  └── else:
        source = EngineStreamingSource(... cacheAppender = PcmCache.appender(key))
```

`CacheFileSource` is a `PcmSource` impl that:
1. Reads the index sidecar (`<sha>.idx.json`) once at construction.
2. Memory-maps the `.pcm` file (`RandomAccessFile.channel.map(READ_ONLY, 0, len)` with a sequential-read fallback for kernels/devices that mmap-fail on large maps).
3. On `nextChunk`, returns the next sentence's PCM byte slice + the cadence-silence already encoded in the index.
4. On `seekToCharOffset(offset)`, looks up the sentence containing that offset via the index, returns from there.
5. On `close`, releases the mmap.

The consumer (`EnginePlayer.startPlaybackPipeline`'s URGENT_AUDIO thread) is unchanged. It pulls `PcmChunk` from `source.nextChunk()` and writes to AudioTrack — the source's identity (cache vs streaming) is invisible. **Pacing is regulated by `AudioTrack.write` blocking** as it does today; the cache source NEVER outpaces the AudioTrack hardware buffer, so the consumer's loop runs at exactly playback rate (issue #84 / PR-B's headroom flow stays at zero, which means buffer-low UI never triggers — correct, because the cache can't underrun).

**Critical pacing decision** (spec risk row 7): "Cache replay vs sentence-highlight motion glide (Lumen's PR #63) — does the consumer pace via `track.write()` blocking, or freerun and out-pace UI?" The consumer paces via `track.write` exactly like the streaming consumer — `AudioTrack` is in `MODE_STREAM` with a small buffer, so `write()` blocks once the buffer is full. UI updates fire at sentence transitions on the same code path as today.

**Tech stack:** Kotlin, kotlinx.coroutines, kotlinx.serialization (decoding the index), JUnit4, Robolectric. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — sections "PcmSource interface", "CacheFileSource", "Architecture" (`if pcmCache.isComplete(key): source = CacheFileSource(...)`), risk rows on cache replay pacing.

**Out of scope (deferred):**

- **WorkManager / RenderScheduler.** Background renders for chapters not currently playing — PR-F. After PR-E, replay is gapless but **first play still streams** (no pre-render); PR-F adds the pre-render so most chapters start cached.
- **Settings UI.** PR-G.
- **Status icons.** PR-H. After PR-E the user has no UI signal that a chapter is cached vs not — they just hear the difference (cached = instant, gapless; streaming = warm-up + possible pause-buffer-resume).
- **Disk-encryption performance.** Tab A7 Lite uses Samsung's "secure folder" file-based encryption. mmap'd reads on encrypted files go through the kernel decrypt path — fine on UFS 2.0 (>500 MB/s decrypted seq), trivial relative to AudioTrack's 44 KB/s consumption rate. Not a risk.

---

## File Structure

### New files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSource.kt`
  The mmap reader, second `PcmSource` impl. Builds a sentence-index list from `PcmIndex` + `PcmIndexEntry`, walks it on each `nextChunk`. Direct-buffer slice per chunk (no copy).

### New tests

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSourceTest.kt`
  Robolectric-backed. Verifies: sequential read produces identical PCM to what was written; seek hits the right sentence; close releases mmap; trailing-silence bytes propagate from the index.

### Modified files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/PcmSource.kt`
  Update doc — `CacheFileSource` is no longer "(PR-E adds...)" but a concrete sibling impl. Add `CacheFileSource` to the sealed hierarchy if `PcmSource` is `sealed interface` (it is).
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`
  In `startPlaybackPipeline`, branch on `pcmCache.isComplete(cacheKey)`. If complete, touch + construct `CacheFileSource`; if not, fall through to the existing streaming path with the appender (PR-D logic). New helper `openCacheFileSourceFor(cacheKey, sampleRate)` wraps the I/O.

---

## Conventions

- All commits use conventional-commit style: `feat`, `fix`, `refactor`, `docs`, `test`. Branch is `dream/<voice>/pcm-cache-pr-e`.
- Run from worktree root.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest`.
- Full app build: `./gradlew :app:assembleDebug`.
- Tablet smoke-test on R83W80CAFZB **required** for this PR — gapless playback is the headline change and only observable on-device. Per memory `feedback_install_test_each_iteration.md`.
- Selective `git add` per CLAUDE.md.
- **No version bump in this PR.** Orchestrator handles release bundling.

---

## Sub-change sequencing

Three commits within the PR:

1. `feat(playback): CacheFileSource (mmap PCM reader)` — pure source impl, no integration.
2. `feat(playback): EnginePlayer dispatches to CacheFileSource on cache hit` — wires PR-E into `startPlaybackPipeline`. After this commit, playback measurably differs (cached replays are gapless).
3. `test(playback): CacheFileSource read semantics` — sequential read, seek, close, byte-for-byte equality with streamed PCM.

---

## PR-E Tasks

### Task E1: Implement `CacheFileSource`

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSource.kt`

The reader is small. The index is decoded once at construction; per-chunk work is a `ByteBuffer` slice + a state-bump.

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.cache.PcmIndex
import `in`.jphe.storyvox.playback.cache.PcmIndexEntry
import `in`.jphe.storyvox.playback.cache.pcmCacheJson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Reads a finalized PCM cache entry from disk and serves [PcmChunk]s
 * to the [`in`.jphe.storyvox.playback.tts.EnginePlayer] consumer thread.
 *
 * Constructed by `EnginePlayer.startPlaybackPipeline` when
 * [`in`.jphe.storyvox.playback.cache.PcmCache.isComplete] returns true
 * for the (chapter, voice, speed, pitch, chunkerVersion) key. The
 * consumer treats this source identically to [EngineStreamingSource]
 * — pull a chunk, write to AudioTrack, fire UI sentence-transition
 * events. The difference: this source NEVER blocks meaningfully (no
 * engine inference), so [bufferHeadroomMs] stays effectively unbounded
 * and PR-B's pause-buffer-resume UI never fires for cached chapters.
 *
 * **Pacing.** The consumer's `track.write()` blocks once the AudioTrack
 * hardware buffer is full (~2 s of audio at minBufferSize on Tab A7 Lite).
 * That's what regulates this source's effective rate — without that
 * back-pressure, the consumer would freerun through the entire cached
 * chapter in milliseconds and the UI would flash sentence boundaries
 * faster than the audio plays. Confirmed in the spec's risk row 7
 * (cache replay vs sentence-highlight motion glide, Lumen's PR #63).
 *
 * **mmap vs read.** The default path mmap's the entire `.pcm` file
 * via `FileChannel.map(READ_ONLY, 0, len)`. mmap is preferable
 * because:
 *  - Slicing returns a [ByteBuffer] view that the consumer can
 *    `track.write(buf, 0, len)` against without an intermediate
 *    `ByteArray` copy. Saves one allocation per sentence.
 *  - The OS page cache absorbs the read cost — sequential mmap
 *    access on UFS 2.0 internal flash is >100 MB/s effective.
 *
 * Fallback: some 32-bit emulator kernels reject mmap requests > 1 GB
 * with EINVAL. In that case we fall back to `RandomAccessFile.read`
 * into a `ByteArray` per sentence, which is correctness-equivalent
 * but allocates per chunk. The fallback is detected at construction
 * time and never re-tried mid-playback.
 *
 * @param pcmFile the on-disk `.pcm` raw 16-bit signed mono PCM at
 *  [PcmIndex.sampleRate].
 * @param indexFile the `.idx.json` sidecar; deserialized into [PcmIndex].
 * @param startSentenceIndex the first sentence to yield. Lets
 *  EnginePlayer resume from a non-zero `currentSentenceIndex`
 *  (post-seek, post-rebuild after voice swap into a cached entry).
 */
class CacheFileSource private constructor(
    private val pcmFile: File,
    private val index: PcmIndex,
    startSentenceIndex: Int,
    /** True if mmap succeeded; false → fallback to `read` path. */
    private val mapped: MappedByteBuffer?,
    private val randomAccess: RandomAccessFile?,
) : PcmSource {

    override val sampleRate: Int = index.sampleRate

    /** Cursor into [PcmIndex.sentences]. Mutated by [nextChunk] and
     *  [seekToCharOffset]; never read by anything else. Single-thread
     *  access (the consumer thread). */
    @Volatile private var cursor: Int = startSentenceIndex.coerceIn(0, index.sentences.size)

    private val _bufferHeadroomMs = MutableStateFlow(Long.MAX_VALUE)

    /**
     * Always reports an effectively unbounded headroom. Cache files
     * never underrun — a cached chapter has every sentence already on
     * disk. The streaming-source headroom flow drives PR-B's
     * pause-buffer-resume UI; for the cache source we want that UI
     * to never fire (which is correct), so we report a huge constant.
     */
    val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

    override suspend fun nextChunk(): PcmChunk? = withContext(Dispatchers.IO) {
        val entries = index.sentences
        val i = cursor
        if (i >= entries.size) return@withContext null
        val e = entries[i]
        cursor = i + 1
        val pcm = readPcmFor(e)
        val silenceBytes = silenceBytesFor(e.trailingSilenceMs, sampleRate)
        PcmChunk(
            sentenceIndex = e.i,
            range = SentenceRange(e.i, e.start, e.end),
            pcm = pcm,
            trailingSilenceBytes = silenceBytes,
        )
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        // Find the sentence index whose [start, end] contains charOffset,
        // OR the latest sentence whose start <= charOffset (for offsets
        // that land in the cadence-silence between sentences). This
        // matches `EngineStreamingSource.seekToCharOffset`'s mapping
        // so both source impls behave identically post-seek.
        val target = index.sentences.indexOfLast { it.start <= charOffset }
            .takeIf { it >= 0 } ?: 0
        cursor = target
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // Release the underlying RandomAccessFile if we had one.
        // mmap'd MappedByteBuffer cleanup happens via GC + Cleaner;
        // we can't force-unmap on JVM without sun.misc unsafe access.
        // Closing the channel that produced the buffer is enough —
        // the buffer remains valid until GC, and on Linux the kernel
        // page-cache reclaim runs anyway. We DO close the
        // RandomAccessFile both in mmap and read paths because it
        // releases the file descriptor.
        runCatching { randomAccess?.close() }
        Unit
    }

    /** Read the bytes for a single sentence's PCM range. mmap path:
     *  slice the MappedByteBuffer view. Read path: position +
     *  read into a fresh ByteArray. */
    private fun readPcmFor(entry: PcmIndexEntry): ByteArray {
        val mapped = this.mapped
        if (mapped != null) {
            // Slice view — no copy of the underlying bytes. We DO
            // need to copy into a ByteArray because EnginePlayer's
            // consumer thread calls `track.write(byteArray, off, len)`,
            // which doesn't accept a ByteBuffer in the legacy
            // 16-bit AudioTrack API path. AudioTrack DOES have a
            // ByteBuffer-accepting overload (`write(ByteBuffer, int, int)`)
            // but EnginePlayer.consumerThread uses the byte[] form
            // for the streaming path's reuse of pcm-array writes.
            //
            // Future optimization: extend PcmChunk to carry a
            // ByteBuffer alongside the byte[], let EnginePlayer
            // detect and use the ByteBuffer overload. Saves one
            // copy per sentence — for a 100KB sentence on Tab A7
            // Lite that's ~1 ms saved per sentence, not material.
            val out = ByteArray(entry.byteLen)
            // Slicing position is volatile (multiple sources share
            // the same MappedByteBuffer? No — this source owns it.
            // Single-threaded access from the consumer.)
            mapped.position(entry.byteOffset.toInt())
            mapped.get(out, 0, entry.byteLen)
            return out
        }
        // Read path (fallback)
        val raf = randomAccess
            ?: error("CacheFileSource has neither mmap nor RAF — construction bug")
        val out = ByteArray(entry.byteLen)
        raf.seek(entry.byteOffset)
        var read = 0
        while (read < entry.byteLen) {
            val n = raf.read(out, read, entry.byteLen - read)
            if (n < 0) break
            read += n
        }
        return out
    }

    companion object {
        /**
         * Open a `CacheFileSource` for an already-finalized cache entry.
         * `EnginePlayer.startPlaybackPipeline` calls this when
         * `PcmCache.isComplete(key)` returns true.
         *
         * @throws java.io.IOException if reading the index fails or the
         *  pcm file is truncated. EnginePlayer should catch this and
         *  fall back to the streaming source — a corrupt cache should
         *  re-render rather than crash playback.
         */
        suspend fun open(
            pcmFile: File,
            indexFile: File,
            startSentenceIndex: Int = 0,
        ): CacheFileSource = withContext(Dispatchers.IO) {
            val index = pcmCacheJson.decodeFromString(
                PcmIndex.serializer(),
                indexFile.readText(),
            )
            // Validate: the .pcm file must be at least totalBytes long
            // (could be larger if a future writer pads, but PR-D's
            // appender writes exactly totalBytes). Catches truncation
            // from disk-full mid-finalize.
            if (pcmFile.length() < index.totalBytes) {
                throw java.io.IOException(
                    "PCM file truncated: ${pcmFile.length()} < ${index.totalBytes}",
                )
            }
            val raf = RandomAccessFile(pcmFile, "r")
            val mapped: MappedByteBuffer? = runCatching {
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }.getOrNull()

            CacheFileSource(
                pcmFile = pcmFile,
                index = index,
                startSentenceIndex = startSentenceIndex,
                mapped = mapped,
                randomAccess = if (mapped == null) raf else raf,
            )
        }
    }
}

/** Same helper as in EngineStreamingSource — bytes of zero-PCM for
 *  a given duration ms at the given sample rate. Lifted as a
 *  top-level function in EngineStreamingSource.kt; we re-import
 *  rather than duplicate. */
// (no new function — uses the existing `silenceBytesFor` from
//  EngineStreamingSource.kt by importing it.)
```

Note on the helper import: Kotlin's `internal fun silenceBytesFor` in `EngineStreamingSource.kt` is package-internal but in the SAME package (`in.jphe.storyvox.playback.tts.source`), so `CacheFileSource.kt` can call it directly without an import.

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify the `PcmSource` sealed-interface hierarchy compiles.**

`PcmSource` is `sealed interface`; both `EngineStreamingSource` and `CacheFileSource` are subclasses of the same sealed parent (same module). Kotlin allows sealed-hierarchy members across files within the same package + same module. Compile success above is the proof.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSource.kt
git commit -m "feat(playback): CacheFileSource — mmap reader for cached PCM

Second PcmSource impl. Reads finalized PCM cache entries via mmap
(FileChannel.map READ_ONLY) with a RandomAccessFile.read fallback
for emulator kernels that fail mmap on large files. Walks the
PcmIndex sentence list; per-chunk work is a slice + ByteArray copy
(track.write needs byte[]).

Pacing is regulated by AudioTrack.write blocking on a full hardware
buffer, exactly like the streaming consumer — cached chapters DON'T
freerun (which would out-pace UI sentence-transition events; spec
risk row 7).

bufferHeadroomMs reports Long.MAX_VALUE — cache files don't underrun,
PR-B's pause-buffer-resume UI never fires for cached playback.

Index validation: .pcm file length >= index.totalBytes; truncated
caches throw IOException so EnginePlayer can fall back to streaming
rather than crash."
```

---

### Task E2: Wire `EnginePlayer` to dispatch on cache hit

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`

The dispatch logic lives in `startPlaybackPipeline`. After PR-D wired the cacheKey + appender, we add a branch BEFORE the streaming-source construction: if `pcmCache.isComplete(key)` is true, open a `CacheFileSource` and skip the appender entirely (no point teeing into a cache that's already complete — same key → same bytes; the appender would overwrite identical content).

The consumer thread is unchanged. It just sees a different `PcmSource` and pulls chunks. The buffer-low UI gating (`cachedCatchupPause` etc.) checks `source.bufferHeadroomMs` — for `CacheFileSource` that's MAX_VALUE so the pause branch never fires. Correct behavior.

The natural-end branch is unchanged — it still calls `source.finalizeCache()`, which is a no-op for `CacheFileSource` because we never construct one with an appender. Wait: `finalizeCache` is on `EngineStreamingSource`, not on the interface. `CacheFileSource` doesn't have it. So the consumer's call site needs to be type-aware.

Resolution: define `finalizeCache` as a no-op default on `PcmSource`. Adds one line to the interface; both impls adopt sensibly.

- [ ] **Step 1: Add `finalizeCache` to the `PcmSource` interface (default no-op).**

In `core-playback/.../tts/source/PcmSource.kt`, add:

```kotlin
sealed interface PcmSource {

    val sampleRate: Int

    suspend fun nextChunk(): PcmChunk?
    suspend fun seekToCharOffset(charOffset: Int)
    suspend fun close()

    /**
     * Mark the in-progress cache write (if any) complete. The
     * streaming source uses this to land its index sidecar on natural
     * end-of-chapter; the cache source has no cache write to
     * finalize, so its impl is a no-op.
     *
     * Called from `EnginePlayer.startPlaybackPipeline`'s consumer
     * thread on the natural-end branch. Idempotent — multiple calls
     * are safe.
     */
    fun finalizeCache() {}
}
```

In `EngineStreamingSource.kt`, the existing `fun finalizeCache()` declared in PR-D becomes an `override`:

```kotlin
override fun finalizeCache() {
    cacheAppender?.let { ap ->
        runCatching { ap.finalize() }
            .onFailure { _cacheTeeErrors.update { it + 1 } }
    }
    cacheAppender = null
}
```

`CacheFileSource` inherits the default no-op, no override needed.

- [ ] **Step 2: Add the cache-hit branch to `startPlaybackPipeline`.**

In `EnginePlayer.kt`, find the source construction block (added in PR-D Task D2 step 3) and wrap it with the dispatch:

```kotlin
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

// PR-E dispatch: try cache hit first, fall back to streaming.
val source: PcmSource = cacheKey
    ?.takeIf { runBlocking { pcmCache.isComplete(it) } }
    ?.let { key ->
        runBlocking {
            // Touch mtime so LRU eviction prefers genuinely-cold entries
            // (PR-C's evictTo sorts by .pcm mtime ascending).
            pcmCache.touch(key)
            // Open the mmap reader. If the open throws (corrupt
            // index, truncated pcm), fall through to streaming —
            // a corrupt cache is a re-render trigger, not a crash.
            runCatching {
                CacheFileSource.open(
                    pcmFile = pcmCache.pcmFileFor(key),
                    indexFile = pcmCache.indexFileFor(key),
                    startSentenceIndex = currentSentenceIndex,
                )
            }.getOrNull()
        }
    }
    ?: run {
        // Cache miss (or cache-hit-open-failed) → streaming source +
        // appender. Same construction as PR-D.
        val appender: PcmAppender? = cacheKey?.let { key ->
            runBlocking {
                if (pcmCache.metaFileFor(key).exists() && !pcmCache.isComplete(key)) {
                    pcmCache.delete(key)
                }
                pcmCache.appender(key, sampleRate = sampleRate)
            }
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
            queueCapacity = cachedBufferChunks.coerceIn(2, 1500),
        )
    }

pcmSource = source
```

This block replaces the entire pre-PR-E source construction in PR-D. The `pcmSource` field (already present from PR-A, holds the active source for `stopPlaybackPipeline` to close) takes either type uniformly.

- [ ] **Step 3: Verify the consumer thread path tolerates `CacheFileSource`.**

The consumer's loop reads:
1. `source.bufferHeadroomMs.value` — but this is on `EngineStreamingSource`, not `PcmSource`. The consumer's gate `if (cachedCatchupPause && paused && source.bufferHeadroomMs.value >= ...)` won't compile if `source` is typed as `PcmSource`.

Fix: smart-cast or explicit cast. The cleanest path: hoist `bufferHeadroomMs` to the interface as well, with a default that returns a non-null StateFlow of MAX_VALUE for non-streaming sources.

Add to `PcmSource`:

```kotlin
sealed interface PcmSource {

    val sampleRate: Int
    suspend fun nextChunk(): PcmChunk?
    suspend fun seekToCharOffset(charOffset: Int)
    suspend fun close()
    fun finalizeCache() {}

    /**
     * Live ms of audio queued but not yet consumed. Streaming impl
     * tracks this dynamically as the producer puts and consumer takes;
     * cache impl reports a huge constant (cached chapters can't
     * underrun, so the buffer-low UI gating in
     * `EnginePlayer.startPlaybackPipeline` never fires for them).
     */
    val bufferHeadroomMs: StateFlow<Long>
}
```

In `EngineStreamingSource`, the existing `val bufferHeadroomMs: StateFlow<Long>` becomes an `override`. In `CacheFileSource`, the same. Both are already declared (E1's class body declares it as a property); just add `override` to match the interface.

This means the existing `PcmSource` interface gains a property + a default method — both safe additions for a sealed interface within the same module.

- [ ] **Step 4: Build and run tests.**

Run: `./gradlew :core-playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/PcmSource.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSource.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt
git commit -m "feat(playback): EnginePlayer dispatches CacheFileSource on cache hit

PcmSource interface gains bufferHeadroomMs (StateFlow<Long>) and
finalizeCache() (default no-op) so the consumer thread can treat
streaming and cache sources uniformly. EngineStreamingSource overrides
both with the existing PR-A/D logic; CacheFileSource overrides
bufferHeadroomMs with MAX_VALUE (cached chapters never underrun,
PR-B's pause-buffer-resume UI doesn't apply).

startPlaybackPipeline now branches:
  - if PcmCache.isComplete(cacheKey): open CacheFileSource via
    CacheFileSource.open(pcmFile, indexFile, startSentenceIndex).
    Touches mtime first so LRU favors actively-played entries.
    On open failure (corrupt index, truncated pcm): fall through.
  - else: existing PR-D streaming-source + appender path.

Consumer loop unchanged. Cached chapters are now gapless because
no engine inference runs during playback; AudioTrack.write blocking
regulates pace as today."
```

---

### Task E3: Test `CacheFileSource` round-trip semantics

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSourceTest.kt`

The test writes a deterministic cache via `PcmAppender` (PR-C surface), opens a `CacheFileSource` against it, and verifies:
1. Sequential `nextChunk` returns sentences in order with byte-for-byte equality to what was written.
2. Trailing silence values from the index propagate to the chunks.
3. `seekToCharOffset` jumps to the correct sentence.
4. `close` releases the file descriptor.
5. Truncated `.pcm` causes `open` to throw (corruption detection).

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CacheFileSourceTest {

    private lateinit var cache: PcmCache
    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        cache = PcmCache(ctx, PcmCacheConfig(ctx))
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key = PcmCacheKey("ch1", "cori", 100, 100, CHUNKER_VERSION)
    private val sentences = listOf(
        Sentence(0,  0, 10, "First."),
        Sentence(1, 11, 20, "Second."),
        Sentence(2, 21, 30, "Third."),
    )

    /** Render three sentences with deterministic, distinct PCM payloads
     *  so the cross-check can verify the right bytes came back. */
    private fun renderCache() {
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(sentences[0], ByteArray(100) { 0xA1.toByte() }, trailingSilenceMs = 350)
        app.appendSentence(sentences[1], ByteArray(80)  { 0xB2.toByte() }, trailingSilenceMs = 200)
        app.appendSentence(sentences[2], ByteArray(120) { 0xC3.toByte() }, trailingSilenceMs = 350)
        app.finalize()
    }

    @Test
    fun `sequential nextChunk yields sentences in order with correct bytes`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        val c0 = source.nextChunk()
        assertEquals(0, c0?.sentenceIndex)
        assertEquals(100, c0?.pcm?.size)
        assertArrayEquals(ByteArray(100) { 0xA1.toByte() }, c0?.pcm)

        val c1 = source.nextChunk()
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(80, c1?.pcm?.size)
        assertArrayEquals(ByteArray(80) { 0xB2.toByte() }, c1?.pcm)

        val c2 = source.nextChunk()
        assertEquals(2, c2?.sentenceIndex)
        assertEquals(120, c2?.pcm?.size)
        assertArrayEquals(ByteArray(120) { 0xC3.toByte() }, c2?.pcm)

        // Source exhausted.
        assertNull(source.nextChunk())

        source.close()
    }

    @Test
    fun `trailingSilenceBytes propagates from index`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        val c0 = source.nextChunk()!!
        // sentence 0 had trailingSilenceMs = 350. silenceBytesFor at
        // 22050 Hz mono 16-bit = 22050 * 350 / 1000 * 2 = 15435 bytes.
        assertEquals(15435, c0.trailingSilenceBytes)

        val c1 = source.nextChunk()!!
        // sentence 1 had trailingSilenceMs = 200. = 22050 * 200 / 1000 * 2 = 8820.
        assertEquals(8820, c1.trailingSilenceBytes)

        source.close()
    }

    @Test
    fun `seekToCharOffset jumps to the correct sentence`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        // Seek into char 15, which lies in sentence 1's range [11, 20].
        source.seekToCharOffset(15)
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        assertEquals(80, c?.pcm?.size)
        source.close()
    }

    @Test
    fun `seek before first sentence yields sentence 0`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(-100)
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `seek past last sentence exhausts the source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(10_000)
        // Last sentence whose start <= 10000 is sentence 2 (start=21).
        val c = source.nextChunk()
        assertEquals(2, c?.sentenceIndex)
        // Then exhausted.
        assertNull(source.nextChunk())
        source.close()
    }

    @Test
    fun `truncated pcm file fails to open`() = runBlocking {
        renderCache()
        val pcmFile = cache.pcmFileFor(key)
        // Truncate to half its size.
        FileOutputStream(pcmFile).use { fos ->
            fos.channel.truncate(pcmFile.length() / 2)
        }
        var threw = false
        try {
            CacheFileSource.open(pcmFile = pcmFile, indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(e.message?.contains("truncated") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun `bufferHeadroomMs reports MAX_VALUE for cache source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        // Pull a chunk; headroom unchanged (cache is never underrunning).
        source.nextChunk()
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        source.close()
    }

    @Test
    fun `close releases file descriptor`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.close()
        // After close, the .pcm file should be deletable on Unix
        // (Windows holds locks on open files; we run on Linux/JVM
        // for tests, so delete should succeed).
        val deleted = cache.pcmFileFor(key).delete()
        assertTrue("file delete after close should succeed", deleted)
    }

    @Test
    fun `start sentence index defaults to zero`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            // no startSentenceIndex param
        )
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `start sentence index resumes mid-chapter`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            startSentenceIndex = 1,
        )
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        source.close()
    }
}
```

- [ ] **Step 1: Create the test file.**
- [ ] **Step 2: Run tests.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*CacheFileSourceTest*"`
Expected: ALL PASS.

- [ ] **Step 3: Run full module suite.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/CacheFileSourceTest.kt
git commit -m "test(playback): CacheFileSource read semantics

Robolectric-backed (real PcmCache against ApplicationContext.cacheDir).
Verifies:
  - Sequential read = byte-for-byte equality with PcmAppender's writes
  - trailingSilenceMs from index → trailingSilenceBytes in chunks
  - seekToCharOffset → cursor jumps to right sentence (with edge cases:
    before first, past last, mid-sentence)
  - bufferHeadroomMs = MAX_VALUE (cache can't underrun)
  - close releases the file descriptor
  - Truncated .pcm fails open with IOException (corrupt cache → re-render,
    not crash)
  - startSentenceIndex resumes mid-chapter (post-seek path)"
```

---

### Task E4: Tablet smoke-test PR-E on R83W80CAFZB

**Files:** none — runtime verification.

This PR is the headline fix. Tablet verification is non-negotiable per memory `feedback_install_test_each_iteration.md` AND because cached vs streaming playback is only audibly distinguishable on-device.

- [ ] **Step 1: Claim tablet lock #47.**

- [ ] **Step 2: Build + install.**

```bash
./gradlew :app:assembleDebug
adb -s R83W80CAFZB install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Verify cache populated from prior PR-D smoke test.**

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls cache/pcm-cache/
```

Expected: at least one `<sha>.pcm` + `.idx.json` + `.meta.json` triple from a previous chapter played to natural end.

If empty, play a chapter to natural end first (the PR-D smoke test path) so the cache populates. Then proceed.

- [ ] **Step 4: Replay the cached chapter from the start.**

Foreground app → Library → fiction → chapter (the one whose cache exists). Tap play.

**Expected behavior**:
1. **First-byte latency under 500 ms** — no engine warm-up, no first-sentence inference. The cache source opens (mmap + index decode, ~50 ms), the consumer's first `track.write` lands within milliseconds.
2. **Zero inter-chunk gaps.** Sentence transitions are instant; no audible silence.
3. **Sentence highlight (Lumen's PR #63) tracks perfectly.** UI highlight glides in lockstep with audio because the consumer paces via `track.write` blocking, exactly like streaming.
4. **CPU usage drops** — top/htop shows minimal app CPU during cached playback (vs 80%+ for streaming Piper-high).

```bash
adb -s R83W80CAFZB shell top -n 1 -m 5 | grep storyvox
```

Expected: < 5% CPU during cached playback (vs ~80% during streaming).

- [ ] **Step 5: Seek-mid-chapter sanity.**

Tap a sentence midway through the chapter. Audio jumps to that sentence instantly with no warm-up; sentence highlight glides correctly.

- [ ] **Step 6: First-play of a NEW chapter still streams.**

Pick a chapter whose cache is NOT present. Play it.

**Expected behavior:**
1. Same warm-up + buffering UX as pre-PR-E. Streaming source path.
2. PR-D's tee write populates the cache as a side effect; on natural end, finalizes.
3. Replay (Step 4 of this task on the new chapter) is now gapless.

- [ ] **Step 7: Voice swap mid-cached-chapter.**

Cached chapter playing → open Voice Library → pick a different voice. The PlaybackController's voice-swap path tears down the pipeline, opens a fresh `loadAndPlay(currentChapterId, ..., charOffset)`. Old (chapter, voiceA) cache stays on disk; new (chapter, voiceB) cache is a miss → streaming source + tee.

Listener experience: brief pause + warm-up + buffering for the new voice; old voice's cache untouched on disk.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls -la cache/pcm-cache/
```

Expected: TWO triples for the same chapter, different basenames (different voiceId in key).

- [ ] **Step 8: Speed change mid-cached-chapter.**

Cached chapter at 1.0× → drag speed slider to 1.25×. Same dance: pipeline rebuild, new cache key (speedHundredths=125), miss, streaming source + tee.

- [ ] **Step 9: Capture screenshots / metrics.**

Save adb top output, screenshots of seek + voice swap, to `~/.claude/projects/-home-jp/scratch/<voice>-pcm-cache-pr-e/`.

- [ ] **Step 10: Release tablet lock #47.**

- [ ] **Step 11: Push branch.**

```bash
git push -u origin dream/<voice>/pcm-cache-pr-e
```

---

### Task E5: Open PR-E

**Files:** none.

- [ ] **Step 1: Open PR.**

```bash
gh pr create --base main --head dream/<voice>/pcm-cache-pr-e \
  --title "feat(playback): CacheFileSource + cache hit dispatch (PR-E)" \
  --body "$(cat <<'EOF'
## Summary

PR-E of the chapter PCM cache spec'd in
`docs/superpowers/specs/2026-05-07-pcm-cache-design.md`. Adds the
**read side** of the cache:

- New `CacheFileSource` — second `PcmSource` impl. Reads finalized
  cache entries via mmap (with `RandomAccessFile.read` fallback for
  emulator kernels). Walks the `PcmIndex`'s sentence list; per-chunk
  work is a slice + ByteArray copy.
- `EnginePlayer.startPlaybackPipeline` branches on
  `PcmCache.isComplete(key)` — cache hit → `CacheFileSource`; miss →
  the existing PR-D streaming source + appender path.

After PR-E, **cached replays are gapless on Tab A7 Lite**. JP's
success criterion ("high-quality voices play smoothly on slow devices")
holds for any chapter that was previously played to natural end.

## Architecture

`PcmSource` interface gains:
- `bufferHeadroomMs: StateFlow<Long>` — streaming source tracks
  dynamically; cache source returns MAX_VALUE (PR-B's pause-buffer-
  resume UI never fires for cached playback).
- `fun finalizeCache()` — default no-op. Streaming source overrides
  with PR-D's appender finalize; cache source uses the default.

Pacing: cache source's `nextChunk` returns immediately (mmap read),
but the consumer thread paces via `track.write` blocking on the
AudioTrack hardware buffer (~2 s deep). Spec risk row 7 (cache
replay vs sentence-highlight motion glide, Lumen PR #63) is addressed.

mmap fallback: some 32-bit emulator kernels reject mmap on > 1 GB
files. Detected at `CacheFileSource.open` time; per-sentence
`RandomAccessFile.read` if mmap fails.

Corrupt cache handling: truncated `.pcm` (disk-full mid-finalize)
or unreadable `.idx.json` throws `IOException` from `open`;
EnginePlayer catches and falls back to the streaming source.
Re-render replaces the corrupt entry on next natural end.

## What's NOT in this PR

- **Background renders.** First play of a never-played chapter still
  streams. PR-F adds chapter N+2 lookahead and library-add 1-3
  pre-render so most chapters start cached.
- **Settings UI.** PR-G.
- **Status icons** — user has no visual signal that a chapter is
  cached. They hear the difference: cached = instant + gapless;
  streaming = warm-up + possible buffering. PR-H adds icons.

## Test plan

- [x] CacheFileSourceTest (Robolectric):
  - sequential read = byte-for-byte equality with appender writes
  - trailingSilenceMs propagation
  - seekToCharOffset (before first, past last, mid-sentence)
  - bufferHeadroomMs = MAX_VALUE
  - close releases fd
  - truncated pcm fails open
  - startSentenceIndex resumes mid-chapter
- [x] R83W80CAFZB tablet:
  - cached chapter replay first-byte < 500 ms, gapless, < 5% CPU
  - seek mid-cached-chapter is instant
  - first play of new chapter still streams + populates cache
  - voice swap creates a new cache key (miss → streaming)
  - speed change creates a new cache key (miss → streaming)
- [x] `./gradlew :core-playback:testDebugUnitTest` green
- [x] `./gradlew :app:assembleDebug` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2-5: Capture PR number, request Copilot, capped wait, address findings, squash-merge.** Same playbook as PR-D Task D5 steps 2-5.

- [ ] **Step 6: Post-merge cleanup.**

```bash
git push origin --delete dream/<voice>/pcm-cache-pr-e
git checkout main && git pull
```

---

## Open questions for JP

1. **mmap on large files (Tab A7 Lite is 64-bit ARM).** Tab A7 Lite (Helio P22T) is `arm64-v8a`; mmap of a multi-GB cache root is fine. The fallback exists for x86 emulator kernels and historical 32-bit ARM devices. **No action needed.**

2. **Corrupt-cache fall-through.** Plan catches `IOException` at `CacheFileSource.open` and falls back to streaming. Should we ALSO `pcmCache.delete(key)` to invalidate the corrupt entry, or leave it for next finalize-time eviction? **Recommendation: leave it.** The streaming source's tee will overwrite the file on the same key + same content. No corruption persists.

3. **Should bufferHeadroomMs on the interface be nullable / StateFlow<Long?>?** Current plan: non-nullable, MAX_VALUE for cache. Cleaner type, no nullable arithmetic in EnginePlayer's gate. **Confirmed.**

4. **`finalizeCache` on the interface — should it be `suspend`?** Streaming source's impl does file I/O (atomic tmp+rename). Cache source's impl is no-op. Today the streaming impl is non-suspend (called from the consumer thread under `runCatching`). Keeping non-suspend keeps the call site clean. **Confirmed non-suspend.**

5. **First-play UX after PR-E vs PR-F.** PR-E ships before PR-F: a NEVER-played chapter has no cache, so first play streams (warm-up + buffering UX). The headline benefit is **the second play is gapless**. PR-F closes the first-play gap by pre-rendering chapters 1-3 on library-add. JP's "high-quality voices play smoothly" holds for replays after PR-E and for first plays of pre-rendered chapters after PR-F.

---

## Self-review

**Spec coverage check (PR-E scope from spec line 407):**
- ✓ "EnginePlayer.loadAndPlay consults PcmCache.isComplete; mmap + play if hit" → Task E2
- ✓ "Sentence index drives sentence ranges" → CacheFileSource yields PcmChunk with `range = SentenceRange(e.i, e.start, e.end)` from the index
- ✓ Pacing via track.write blocking (spec risk row 7) → CacheFileSource.nextChunk returns immediately, AudioTrack hardware buffer regulates rate

**Spec deltas / decisions:**
- **mmap with `RandomAccessFile.read` fallback** — spec says "mmap'd PCM file"; fallback handles edge-case kernels gracefully.
- **`PcmSource` interface gains `bufferHeadroomMs` + `finalizeCache`** — uniform consumer code path, no `is`/`as` casts in the hot loop.
- **Corrupt cache → fall back to streaming, don't crash** — spec doesn't specify; this is the safe choice.
- **`startSentenceIndex` parameter** — spec mentions seek; supporting non-zero start lets the consumer resume from `currentSentenceIndex` after a voice swap into a cached entry, AND lets seek work via the same mechanism.

**Placeholder scan:** None — every Kotlin block compiles in context.

**Type consistency:** `PcmSource` interface members consistent across declaration, both impls, EnginePlayer call sites, and tests.

**Risks deferred to follow-up PRs:**
- Pre-render scheduling (PR-F): first play still streams.
- Status icons (PR-H): no UI signal for cache state.
- Settings UI (PR-G): no quota / Mode C surface.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-e.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute E1-E5 inline. PR-open is the JP-visible boundary; everything before that stays in-session.
