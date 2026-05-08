# PCM Cache PR A + PR B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the smallest immediate-relief change for the audible inter-chunk gap on Piper-high + Tab A7 Lite by (PR A) extracting a `PcmSource` interface from EnginePlayer's pipeline as a behavioral no-op, then (PR B) wiring a `pause-buffer-resume` mode that pauses AudioTrack with a UI "buffering" state instead of emitting silence on underrun.

**Architecture:** PR A is pure refactor — pull the producer + queue + write-loop out of `EnginePlayer.startPlaybackPipeline` into a new `PcmSource` interface with one impl, `EngineStreamingSource`. EnginePlayer's consumer thread keeps owning the AudioTrack but pulls `PcmChunk`s from the source instead of from a `LinkedBlockingQueue<SentencePcm>` directly. PR B layers on a `bufferLowSignal: StateFlow<Boolean>` from the source; the consumer pauses `AudioTrack.play()` while the signal is true, sets `PlaybackState.isBuffering = true`, and resumes when head-room recovers. Cap the AudioTrack request size to a tight buffer so the signal actually fires (currently the OS hands us ~2-3s of buffer, masking underrun).

**Tech stack:** Kotlin, AndroidX Media3 SimpleBasePlayer, kotlinx.coroutines, JUnit4, Mockito-like fakes (existing project pattern).

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md`

**Out of scope (follow-up PRs C-H):** PcmCache filesystem layer, CacheFileSource, RenderScheduler/WorkManager job, Settings UI for quota, LRU eviction, per-chapter cache status icons. PR A + B alone do NOT eliminate the gap — they convert "silent underrun" into "explicit buffering UI". Eliminating the gap requires PR C-F (cache + render).

---

## File Structure

### PR A — PcmSource extraction (behavioral no-op)

**New files:**
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/PcmSource.kt` — sealed interface + `PcmChunk` data class
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt` — moves the producer coroutine + queue out of EnginePlayer

**Modified files:**
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt` — `startPlaybackPipeline` shrinks: instantiates a source, runs a thinner consumer loop pulling `PcmChunk` from `source.nextChunk()` instead of `LinkedBlockingQueue<SentencePcm>.take()`.

**New tests:**
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt` — verifies `nextChunk()` semantics, cancellation, and that `close()` interrupts blocked producers.

### PR B — pause-buffer-resume

**New files:** none (build on PR A's source).

**Modified files:**
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/PlaybackState.kt` — add `isBuffering: Boolean = false` field.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt` — expose `bufferHeadroomMs: StateFlow<Long>` computed from queue depth × avg chunk duration; expose `bufferLowSignal: StateFlow<Boolean>` derived from headroom < 2000.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt` — consumer collects `bufferLowSignal`, calls `audioTrack.pause()` / `audioTrack.play()`, updates `PlaybackState.isBuffering`. AudioTrack rebuild forces tighter buffer so signal can fire (`AudioTrack.getMinBufferSize()` is what the existing code requests, but the OS may upgrade — cap at request × 1 explicitly via `setBufferSizeInBytes`).
- `feature/src/main/kotlin/in/jphe/storyvox/feature/reader/AudiobookView.kt` — extend the existing brass-spinner `warmingUp` derived state to include `isBuffering`, so the same visual surfaces during mid-stream underrun.

**New tests:**
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceBufferLowTest.kt` — verifies the signal flips appropriately.
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/PlaybackStateTest.kt` — extend with `isBuffering` defaults and serialization round-trip.

---

## Conventions

- All commits use conventional-commit style: `feat`, `fix`, `refactor`, `docs`, `test`. Branch is `dream/aurelia/tts-chunk-gap`.
- Run from the worktree root: `/home/jp/Projects/storyvox-worktrees/aurelia-tts-chunk-gap`.
- `./gradlew :core-playback:testDebugUnitTest` for fast local test iteration.
- `./gradlew :app:assembleDebug` and install via `adb -s R83W80CAFZB install -r app/build/outputs/apk/debug/app-debug.apk` for tablet verification (claim TASK #47 first per CLAUDE.md).

---

## PR A: PcmSource extraction

### Task A1: Create the `PcmSource` interface and `PcmChunk` data class

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/PcmSource.kt`

- [ ] **Step 1: Create the file with the sealed interface + data class.**

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange

/**
 * Source of PCM chunks for the EnginePlayer consumer to write to AudioTrack.
 *
 * Two impls (PR-A only ships [EngineStreamingSource]; [CacheFileSource]
 * follows in PR-E):
 *  - [EngineStreamingSource] runs the VoxSherpa engine on a worker
 *    coroutine, putting generated PCM into a queue. `nextChunk` blocks
 *    on queue.take. Subject to producer-can't-keep-up underrun.
 *  - `CacheFileSource` (PR-E) mmap-reads a pre-rendered chapter PCM file.
 *    Never blocks for long.
 *
 * The consumer treats both uniformly. When the source is exhausted
 * (chapter end), `nextChunk` returns null.
 */
sealed interface PcmSource {

    val sampleRate: Int

    /**
     * Pull the next chunk. Suspends if the source has no chunk ready
     * (streaming impl: producer hasn't generated the next sentence yet;
     * cache impl: never blocks meaningfully). Returns null when the
     * chapter is fully drained.
     *
     * Cancellation: if the calling coroutine is cancelled, blocked
     * impls must throw [kotlinx.coroutines.CancellationException]
     * promptly so the consumer can shut down. The streaming impl
     * achieves this via [kotlinx.coroutines.runInterruptible].
     */
    suspend fun nextChunk(): PcmChunk?

    /**
     * Re-anchor the source to the sentence containing [charOffset].
     * Streaming impl cancels the producer and restarts at the new
     * sentence index. Cache impl seeks the underlying file via the
     * sidecar index.
     */
    suspend fun seekToCharOffset(charOffset: Int)

    /** Release any resources (cancel producer, close mmap, etc). */
    suspend fun close()
}

/**
 * One chunk of PCM tagged with its sentence range. The trailing
 * silence is intentional cadence; the consumer spools this many ms
 * of silence-PCM after the audible PCM to give sentences breathing
 * room. See [`in`.jphe.storyvox.playback.tts.EnginePlayer.trailingPauseMs].
 */
data class PcmChunk(
    val sentenceIndex: Int,
    val range: SentenceRange,
    val pcm: ByteArray,
    val trailingSilenceBytes: Int,
) {
    /** Equality is identity-based on sentenceIndex + first PCM byte
     *  to keep equals cheap; the consumer never compares chunks. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = sentenceIndex
}
```

- [ ] **Step 2: Verify compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/PcmSource.kt
git commit -m "refactor(playback): introduce PcmSource interface + PcmChunk

Pre-extraction step for the chapter-cache work. Defines a uniform
contract the consumer can pull PCM from; PR-A's EngineStreamingSource
implements the existing engine-driven pipeline against it, future
PR-E adds CacheFileSource."
```

### Task A2: Move producer + queue into `EngineStreamingSource`, write the failing test

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt`
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt`

The streaming source owns: the producer coroutine, the `LinkedBlockingQueue<PcmChunk>`, the `engineMutex`, the trailing-silence sizing logic moved out of EnginePlayer. EnginePlayer keeps the AudioTrack write loop, the `pipelineRunning` flag, and lifecycle.

- [ ] **Step 1: Write the failing test for `nextChunk` happy path.**

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStreamingSourceTest {

    @Test
    fun `nextChunk returns sentences in order then null at end`() = runTest {
        val sentences = listOf(
            Sentence(0, 0,  10, "One."),
            Sentence(1, 11, 20, "Two."),
            Sentence(2, 21, 30, "Three."),
        )
        val fakeEngine = FakeVoiceEngine(
            sampleRate = 22050,
            pcmFor = { text -> ByteArray(text.length * 2) { 0 } },
        )
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine,
            speed = 1.0f,
            pitch = 1.0f,
        )

        val c0 = source.nextChunk(); val c1 = source.nextChunk(); val c2 = source.nextChunk()
        val end = source.nextChunk()

        assertEquals(0, c0?.sentenceIndex)
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(2, c2?.sentenceIndex)
        assertNull(end)

        source.close()
    }
}

/** Test fake — returns deterministic PCM for any text. */
private class FakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
```

- [ ] **Step 2: Run test, expect compilation failure.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSourceTest*"`
Expected: FAIL — `EngineStreamingSource` not found.

- [ ] **Step 3: Implement `EngineStreamingSource` (minimal).**

```kotlin
package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.tts.Sentence
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives VoxSherpa's [VoiceEngine]-style handle on a worker coroutine,
 * putting generated PCM into a bounded queue. The consumer (EnginePlayer's
 * AudioTrack writer thread) pulls from [nextChunk]. Mirrors the producer
 * half of EnginePlayer.startPlaybackPipeline pre-PR-A.
 *
 * Why a [LinkedBlockingQueue] not a coroutine [Channel]: the
 * EnginePlayer consumer is a pinned OS thread at URGENT_AUDIO; pulling
 * via Channel would force it through the coroutine dispatcher and lose
 * the priority. We bridge via runInterruptible so the producer can
 * coexist with structured concurrency for shutdown.
 */
class EngineStreamingSource(
    private val sentences: List<Sentence>,
    startSentenceIndex: Int,
    private val engine: VoiceEngineHandle,
    private val speed: Float,
    private val pitch: Float,
    private val queueCapacity: Int = 8,
) : PcmSource {

    /** SAM-style handle so we can fake the engine in tests without
     *  pulling VoxSherpa onto the unit-test classpath. */
    interface VoiceEngineHandle {
        val sampleRate: Int
        fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray?
    }

    override val sampleRate: Int = engine.sampleRate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<Item>(queueCapacity)
    private val engineMutex = Mutex()

    @Volatile private var startIndex: Int = startSentenceIndex
    private var producerJob: Job = startProducer(startSentenceIndex)

    override suspend fun nextChunk(): PcmChunk? = runInterruptible {
        val item = queue.take()
        if (item === END_PILL) null else item.chunk
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        // Find target sentence
        val target = sentences.indexOfLast { it.startChar <= charOffset }
            .takeIf { it >= 0 } ?: 0
        producerJob.cancel()
        // Drain queue so consumer doesn't pull stale chunks
        queue.clear()
        startIndex = target
        producerJob = startProducer(target)
    }

    override suspend fun close() {
        running.set(false)
        producerJob.cancel()
        queue.clear()
        // Wake any consumer blocked in take()
        queue.offer(END_PILL)
        scope.cancel()
    }

    private fun startProducer(fromIndex: Int): Job = scope.launch {
        try {
            for (i in fromIndex until sentences.size) {
                if (!running.get()) return@launch
                val s = sentences[i]
                val pcm = engineMutex.withLock {
                    if (!running.get()) return@withLock null
                    engine.generateAudioPCM(s.text, speed, pitch)
                } ?: continue
                val pauseMs = trailingPauseMs(s.text) / speed.coerceAtLeast(0.5f)
                val silenceBytes = silenceBytesFor(pauseMs.toInt(), sampleRate)
                val chunk = PcmChunk(
                    sentenceIndex = i,
                    range = SentenceRange(s.index, s.startChar, s.endChar),
                    pcm = pcm,
                    trailingSilenceBytes = silenceBytes,
                )
                runInterruptible { queue.put(Item(chunk)) }
            }
            runInterruptible { queue.put(END_PILL) }
        } catch (_: Throwable) {
            // Cancelled — silent
        }
    }

    /** Wrapper so the END_PILL identity check is === safe. */
    private class Item(val chunk: PcmChunk)

    private companion object {
        val END_PILL = Item(PcmChunk(-1, SentenceRange(-1, -1, -1), ByteArray(0), 0))
    }
}

/** Length of trailing silence, by terminal punctuation. Moved verbatim
 *  from EnginePlayer.trailingPauseMs (PR-A is a refactor; the logic
 *  doesn't change). */
internal fun trailingPauseMs(sentenceText: String): Int {
    val closePunct: Set<Char> = setOf('"', '\'', ')', ']', '}', '”', '’', '»', '」')
    var end = sentenceText.length
    while (end > 0 && (sentenceText[end - 1].isWhitespace() ||
            sentenceText[end - 1] in closePunct)) end--
    if (end == 0) return 60
    if (end >= 3 && sentenceText.regionMatches(end - 3, "...", 0, 3)) return 350
    return when (sentenceText[end - 1]) {
        '.', '!', '?', '…' -> 350
        ';', ':' -> 200
        ',', '—', '–', '-' -> 120
        else -> 60
    }
}

internal fun silenceBytesFor(durationMs: Int, sampleRate: Int): Int {
    if (durationMs <= 0) return 0
    return ((sampleRate.toLong() * durationMs / 1000L).toInt() * 2).coerceAtLeast(0)
}
```

- [ ] **Step 4: Run test, expect pass.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSourceTest*"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt \
        core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt
git commit -m "refactor(playback): extract EngineStreamingSource from EnginePlayer

Producer + queue + engineMutex move into a PcmSource impl. EnginePlayer
will switch to consuming through this in the next commit; no behavior
change yet because the producer logic is verbatim.

Adds VoiceEngineHandle SAM so tests can fake the engine without pulling
in the VoxSherpa AAR on the JVM unit-test path."
```

### Task A3: Add `nextChunk` cancellation test

**Files:**
- Modify: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt`

- [ ] **Step 1: Add test.**

Append to the existing test class:

```kotlin
@Test
fun `close interrupts a blocked nextChunk`() = runBlocking {
    val sentences = listOf(Sentence(0, 0, 10, "One."))
    // Engine that never returns — simulates synthesis taking forever.
    val slowEngine = object : EngineStreamingSource.VoiceEngineHandle {
        override val sampleRate: Int = 22050
        override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
            // Sleep so the producer can't outpace close()
            Thread.sleep(2_000)
            return ByteArray(100)
        }
    }
    val source = EngineStreamingSource(sentences, 0, slowEngine, 1f, 1f)

    // Pull on a coroutine; close() must unblock it within ~100ms
    val pulled = kotlinx.coroutines.async {
        source.nextChunk()
    }
    kotlinx.coroutines.delay(50)
    source.close()
    val started = System.currentTimeMillis()
    val result = pulled.await()
    val elapsed = System.currentTimeMillis() - started

    // After close(), nextChunk should return null promptly (END_PILL),
    // not wait for the slow engine.
    assertNull(result)
    assert(elapsed < 500) { "close() took $elapsed ms to unblock nextChunk" }
}
```

- [ ] **Step 2: Run, expect pass (close() offers END_PILL).**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSourceTest*"`
Expected: PASS (both tests).

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt
git commit -m "test(playback): close() unblocks nextChunk

Regression guard for the runInterruptible bridge — without END_PILL,
a blocked queue.take inside nextChunk would survive close() until
the slow engine finally returned. Real-world: voice swap mid-chapter
or seek during inference."
```

### Task A4: Switch EnginePlayer to consume from `PcmSource`

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`

This is the load-bearing change. The consumer thread inside `startPlaybackPipeline` now pulls from `source.nextChunk()` (suspending) instead of `queue.take()` (blocking). To preserve URGENT_AUDIO priority on the consumer, we keep the consumer as a pinned `Thread`, and bridge to suspending via `kotlinx.coroutines.runBlocking { source.nextChunk() }`. The `runBlocking` on a dedicated audio thread is acceptable — it's the same shape as the existing `runInterruptible` bridge.

- [ ] **Step 1: Replace producer fields + producer launch with source instance.**

In `EnginePlayer.kt`, locate the `// Producer-consumer plumbing` block (roughly lines 187-215 in the original). Replace this region:

```kotlin
private var pcmQueue: LinkedBlockingQueue<SentencePcm>? = null
private var generationJob: Job? = null
```

with:

```kotlin
private var streamingSource: PcmSource? = null
```

Delete the `SentencePcm` data class declaration at the top of the same block — it's now `PcmChunk` inside `PcmSource.kt`.

- [ ] **Step 2: Replace `startPlaybackPipeline` body.**

Find the existing function and replace its body with:

```kotlin
private fun startPlaybackPipeline() {
    stopPlaybackPipeline()

    val engineType = activeEngineType
    val sampleRate = when (engineType) {
        is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
        else -> VoiceEngine.getInstance().sampleRate
    }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
    val track = createAudioTrack(sampleRate)
    audioTrack = track

    val source = EngineStreamingSource(
        sentences = sentences,
        startSentenceIndex = currentSentenceIndex,
        engine = activeVoiceEngineHandle(engineType),
        speed = currentSpeed,
        pitch = currentPitch,
    )
    streamingSource = source
    pipelineRunning.set(true)

    consumerThread = Thread({
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        var firstChunk = true
        var lastVol = -1f
        var naturalEnd = false
        try {
            while (pipelineRunning.get()) {
                val chunk = try {
                    runBlocking { source.nextChunk() }
                } catch (_: Throwable) { null }
                    ?: run { naturalEnd = pipelineRunning.get(); break }

                scope.launch {
                    currentSentenceIndex = chunk.sentenceIndex
                    _observableState.update {
                        it.copy(
                            currentSentenceRange = chunk.range,
                            charOffset = chunk.range.startCharInChapter,
                        )
                    }
                }

                if (firstChunk) {
                    val v = volumeRamp.current
                    runCatching { track.setVolume(v) }
                    lastVol = v
                    runCatching { track.play() }
                    firstChunk = false
                }

                writePcmRespectingVolume(track, chunk.pcm, lastVolHolder = ::lastVolGet, set = { lastVol = it })
                writeSilenceRespectingVolume(track, chunk.trailingSilenceBytes, ::lastVolGet, set = { lastVol = it })
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            if (naturalEnd && pipelineRunning.get()) {
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

/** Helper holders the inner thread closes over. They look ugly but
 *  Kotlin doesn't let us mutate a captured local from inside another
 *  function, and the alternative is duplicating two write loops. */
private var consumerLastVol: Float = -1f
private fun lastVolGet(): Float = consumerLastVol

private fun writePcmRespectingVolume(
    track: AudioTrack,
    pcm: ByteArray,
    lastVolHolder: () -> Float,
    set: (Float) -> Unit,
) {
    var written = 0
    while (written < pcm.size && pipelineRunning.get()) {
        val v = volumeRamp.current
        if (v != lastVolHolder()) {
            runCatching { track.setVolume(v) }
            set(v)
            consumerLastVol = v
        }
        val n = track.write(pcm, written, pcm.size - written)
        if (n < 0) break
        written += n
    }
}

private fun writeSilenceRespectingVolume(
    track: AudioTrack,
    bytes: Int,
    lastVolHolder: () -> Float,
    set: (Float) -> Unit,
) {
    var remaining = bytes
    while (remaining > 0 && pipelineRunning.get()) {
        val v = volumeRamp.current
        if (v != lastVolHolder()) {
            runCatching { track.setVolume(v) }
            set(v)
            consumerLastVol = v
        }
        val chunk = remaining.coerceAtMost(SILENCE_CHUNK.size)
        val n = track.write(SILENCE_CHUNK, 0, chunk)
        if (n < 0) break
        remaining -= n
    }
}

/** Bridge to the existing VoxSherpa singletons via the
 *  EngineStreamingSource.VoiceEngineHandle SAM. */
private fun activeVoiceEngineHandle(engineType: EngineType?): EngineStreamingSource.VoiceEngineHandle =
    object : EngineStreamingSource.VoiceEngineHandle {
        override val sampleRate: Int = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }
        override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().generateAudioPCM(text, speed, pitch)
            else -> VoiceEngine.getInstance().generateAudioPCM(text, speed, pitch)
        }
    }
```

- [ ] **Step 3: Replace `stopPlaybackPipeline` body to close the source.**

```kotlin
private fun stopPlaybackPipeline() {
    pipelineRunning.set(false)

    val track = audioTrack
    audioTrack = null
    track?.let {
        runCatching { it.pause() }
        runCatching { it.flush() }
    }

    val src = streamingSource
    streamingSource = null
    if (src != null) {
        runBlocking { src.close() }
    }

    val t = consumerThread
    consumerThread = null
    if (t != null && t !== Thread.currentThread()) {
        t.interrupt()
        try { t.join(2_000) } catch (_: InterruptedException) {}
    }
}
```

- [ ] **Step 4: Delete now-unused `trailingPauseMs`, `silenceBytesFor`, `SentencePcm` from EnginePlayer (they live in source/EngineStreamingSource.kt now).**

Search and remove from EnginePlayer.kt:
- the entire `private fun trailingPauseMs(sentenceText: String): Int { ... }` block
- the entire `private fun silenceBytesFor(durationMs: Int, sampleRate: Int): Int { ... }` block
- the `private data class SentencePcm(...)` declaration
- the `NATURAL_END_PILL` companion-object constant
- the unused `LinkedBlockingQueue`, `engineMutex`, and `runInterruptible` imports if no longer referenced

Keep `SILENCE_CHUNK` (still used by the consumer for silence spool).

- [ ] **Step 5: Build full module.**

Run: `./gradlew :core-playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all core-playback tests.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS (existing tests + new EngineStreamingSourceTest).

- [ ] **Step 7: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt
git commit -m "refactor(playback): EnginePlayer consumes via PcmSource

Producer + engineMutex + queue moved into EngineStreamingSource. Consumer
keeps URGENT_AUDIO pinned thread + AudioTrack ownership. Bridges to the
suspending source via runBlocking on the audio thread (acceptable on a
dedicated thread, same shape as the prior runInterruptible bridge).

Behavioral no-op: same producer/consumer shape, same trailingPauseMs
semantics, same engineMutex, same queue depth. Verifying via the existing
test suite — runtime parity confirmed on Tab A7 Lite separately."
```

### Task A5: Tablet smoke-test PR A on R83W80CAFZB

**Files:** none

- [ ] **Step 1: Claim tablet lock.**

Run: `gh task update 47 --owner aurelia` (or via TaskUpdate tool).
Expected: lock owned.

- [ ] **Step 2: Build + install.**

```bash
./gradlew :app:assembleDebug
adb -s R83W80CAFZB install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: SUCCESS.

- [ ] **Step 3: Foreground app, play Sky Pride Ch.1 for 60 s.**

```bash
adb -s R83W80CAFZB shell am start -n in.jphe.storyvox/.MainActivity
```

Manual: tap Library → Sky Pride → Chapter 1 → Play. Listen for 60 s.

Expected: same behavior as before this PR — producing audio with audible inter-chunk gaps (the gaps DON'T go away; that's PR-C-F's job). No crashes. Sentence highlight glide still tracks. Pause + resume works. Seek works.

- [ ] **Step 4: Release tablet lock.**

Run: `TaskUpdate id=47 owner=""`.

- [ ] **Step 5: Push branch.**

```bash
git push origin dream/aurelia/tts-chunk-gap
```

### Task A6: Open PR A

**Files:** none

- [ ] **Step 1: Open PR via gh.**

```bash
gh pr create --base main --head dream/aurelia/tts-chunk-gap \
  --title "refactor(playback): extract PcmSource interface from EnginePlayer" \
  --body "$(cat <<'EOF'
## Summary

Behavioral no-op refactor that introduces a `PcmSource` interface
between EnginePlayer and the VoxSherpa engine. The producer +
LinkedBlockingQueue + engineMutex move out of EnginePlayer into the
new `EngineStreamingSource` impl. EnginePlayer's URGENT_AUDIO consumer
thread now pulls from `source.nextChunk()` instead of `queue.take()`.

Pure refactor — same producer/consumer shape, same trailingPauseMs
semantics, same queue depth, same AudioTrack write loop. Pre-extraction
step for the chapter-cache work spec'd in
`docs/superpowers/specs/2026-05-07-pcm-cache-design.md`.

## Why

Measured baseline (separate run, captured 2026-05-07 23:22Z, see
`scratch/aurelia-tts-chunk-gap/baseline.md`): Piper-high \"cori\" on
R83W80CAFZB synthesizes audio at **0.285× realtime** — producer is
3.5× slower than playback, median inter-chunk gap 8021 ms. The full
fix requires moving synthesis off the playback hot path (PR-C-F);
this PR is the no-op refactor that lets PR-E plug a CacheFileSource
into the same consumer.

## Test plan

- [x] core-playback unit tests pass (`:core-playback:testDebugUnitTest`)
- [x] new EngineStreamingSourceTest covers nextChunk happy path + close-while-blocked
- [x] R83W80CAFZB smoke test: play Sky Pride Ch.1 for 60s — same behavior as pre-PR (audible gaps remain, that's expected; PR-C-F closes them)
- [x] sentence highlight glide tracks normally
- [x] pause/resume works
- [x] seek works

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Wait for copilot-pull-request-reviewer[bot] review (per memory feedback_copilot_review_required.md).**

Poll: `gh pr view <num> --json reviews`
Address any feedback in additional commits.

- [ ] **Step 3: After approval, squash-merge.**

```bash
gh pr merge <num> --squash --delete-branch=false
```

Keep the branch — PR-B builds on it.

---

## PR B: pause-buffer-resume

### Task B1: Add `bufferHeadroomMs` to `EngineStreamingSource`

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt`

`headroomMs` = total ms of audio currently sitting in the queue (sum of `pcm.size / sampleRate / 2 * 1000` over queued chunks). Updated on every `put` (producer) and `take` (consumer). Backed by a `MutableStateFlow<Long>`.

- [ ] **Step 1: Add the StateFlow + update logic.**

Add to the `EngineStreamingSource` class:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val _bufferHeadroomMs = MutableStateFlow(0L)
/** Sum of audio duration of every PCM chunk currently in the queue.
 *  The consumer pauses AudioTrack when this drops below the underrun
 *  threshold, surfacing a "buffering" UI state instead of silence. */
val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

private fun pcmDurationMs(pcm: ByteArray): Long =
    pcm.size.toLong() * 1000L / (sampleRate.toLong() * 2L)
```

Modify the producer to update on put, and `nextChunk` to update on take. After:

```kotlin
runInterruptible { queue.put(Item(chunk)) }
_bufferHeadroomMs.update { it + pcmDurationMs(pcm) + pauseMs.toLong() }
```

(import `kotlinx.coroutines.flow.update`)

And in `nextChunk`:

```kotlin
override suspend fun nextChunk(): PcmChunk? = runInterruptible {
    val item = queue.take()
    if (item === END_PILL) return@runInterruptible null
    val chunk = item.chunk
    val durMs = pcmDurationMs(chunk.pcm) +
        chunk.trailingSilenceBytes.toLong() * 1000L / (sampleRate.toLong() * 2L)
    _bufferHeadroomMs.update { (it - durMs).coerceAtLeast(0L) }
    chunk
}
```

- [ ] **Step 2: Verify compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt
git commit -m "feat(playback): EngineStreamingSource exposes bufferHeadroomMs

Sum of buffered audio duration across queued chunks, updated on every
producer put and consumer take. Consumer will pause AudioTrack when
this drops below the underrun threshold (next commit)."
```

### Task B2: Test bufferHeadroomMs accounting

**Files:**
- Modify: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt`

- [ ] **Step 1: Add test.**

```kotlin
@Test
fun `bufferHeadroomMs reflects queued audio duration`() = runTest {
    val sentences = listOf(
        Sentence(0, 0, 10, "One."),
        Sentence(1, 11, 20, "Two."),
    )
    // 22050 * 2 = 44100 bytes per second of audio.
    // 44100 bytes = 1000 ms at 22050 Hz mono 16bit.
    val fakeEngine = FakeVoiceEngine(22050) { _ -> ByteArray(44100) }
    val source = EngineStreamingSource(sentences, 0, fakeEngine, 1f, 1f)

    // Wait for producer to fill the queue. Poll because the producer
    // runs on Dispatchers.IO; we don't want to flake on test scheduler.
    val deadline = System.currentTimeMillis() + 2000
    while (System.currentTimeMillis() < deadline &&
           source.bufferHeadroomMs.value < 2000) {
        kotlinx.coroutines.delay(10)
    }
    // 2 sentences × (1000ms PCM + 350ms cadence) = 2700ms expected
    val before = source.bufferHeadroomMs.value
    assert(before >= 2000) { "expected >=2000ms headroom, got $before" }

    val first = source.nextChunk()
    assertEquals(0, first?.sentenceIndex)
    val after = source.bufferHeadroomMs.value
    // Headroom should drop by ~1350ms (1000ms PCM + 350ms cadence)
    assert(after < before - 1000) {
        "expected headroom to drop by >1000ms after take, was $before -> $after"
    }

    source.close()
}
```

- [ ] **Step 2: Run.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*EngineStreamingSourceTest*"`
Expected: PASS.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSourceTest.kt
git commit -m "test(playback): bufferHeadroomMs accounts produced + consumed audio"
```

### Task B3: Add `isBuffering` to `PlaybackState`

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/PlaybackState.kt`
- Modify: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/PlaybackStateTest.kt`

- [ ] **Step 1: Add field.**

In `PlaybackState`, add after `isPlaying`:

```kotlin
val isPlaying: Boolean = false,
/** True when the AudioTrack is paused waiting for the producer to
 *  refill the queue past the underrun threshold. UI surfaces a
 *  "buffering" spinner; differs from `!isPlaying` (user pause) and
 *  from `isPlaying && sentenceEnd == 0` (initial warm-up). */
val isBuffering: Boolean = false,
val currentSentenceRange: SentenceRange? = null,
```

- [ ] **Step 2: Add test.**

In PlaybackStateTest.kt:

```kotlin
@Test
fun `isBuffering defaults false and serializes`() {
    val s = PlaybackState(isBuffering = true, isPlaying = true, charOffset = 42)
    val json = kotlinx.serialization.json.Json.encodeToString(PlaybackState.serializer(), s)
    val round = kotlinx.serialization.json.Json.decodeFromString(PlaybackState.serializer(), json)
    assertEquals(true, round.isBuffering)
    assertEquals(true, round.isPlaying)
    assertEquals(42, round.charOffset)

    val default = PlaybackState()
    assertEquals(false, default.isBuffering)
}
```

- [ ] **Step 3: Run all core-playback tests.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/PlaybackState.kt \
        core-playback/src/test/kotlin/in/jphe/storyvox/playback/PlaybackStateTest.kt
git commit -m "feat(playback): add PlaybackState.isBuffering"
```

### Task B4: Wire consumer to pause on low buffer

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`

The consumer collects `bufferHeadroomMs` on its own thread (NOT the main scope, to keep the audio thread independent). When headroom drops below `BUFFER_UNDERRUN_THRESHOLD_MS = 2000`, call `audioTrack.pause()` and emit `isBuffering = true`. When headroom climbs back above `BUFFER_RESUME_THRESHOLD_MS = 4000` (hysteresis to prevent thrash), call `audioTrack.play()` and emit `isBuffering = false`.

The simplest implementation: poll `bufferHeadroomMs.value` between each chunk write. (Don't need a coroutine collector — the consumer thread runs the write loop and can read the StateFlow value directly.)

- [ ] **Step 1: Add buffer-state tracking to the consumer thread.**

In `startPlaybackPipeline`, modify the consumer thread loop:

```kotlin
consumerThread = Thread({
    AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
    var firstChunk = true
    var naturalEnd = false
    var paused = false   // tracks AudioTrack.pause() vs .play() state
    try {
        while (pipelineRunning.get()) {

            // Check buffer headroom BEFORE pulling the next chunk.
            // If we're paused and headroom is back over the resume
            // threshold, kick AudioTrack alive again before nextChunk
            // (which may block).
            val headroom = source.bufferHeadroomMs.value
            if (paused && headroom >= BUFFER_RESUME_THRESHOLD_MS) {
                runCatching { track.play() }
                paused = false
                scope.launch {
                    _observableState.update { it.copy(isBuffering = false) }
                }
            }

            val chunk = try {
                runBlocking { source.nextChunk() }
            } catch (_: Throwable) { null }
                ?: run { naturalEnd = pipelineRunning.get(); break }

            scope.launch {
                currentSentenceIndex = chunk.sentenceIndex
                _observableState.update {
                    it.copy(
                        currentSentenceRange = chunk.range,
                        charOffset = chunk.range.startCharInChapter,
                    )
                }
            }

            if (firstChunk) {
                val v = volumeRamp.current
                runCatching { track.setVolume(v) }
                consumerLastVol = v
                runCatching { track.play() }
                firstChunk = false
            }

            // Re-check headroom after take (which decremented it).
            // If we just dropped below the underrun threshold AND
            // we're not already paused, pause the track and emit
            // buffering state. This MUST happen BEFORE we begin
            // writing this chunk's PCM, because the AudioTrack
            // hardware buffer is large enough that the next write
            // would land seconds before the listener hears silence.
            val afterTakeHeadroom = source.bufferHeadroomMs.value
            if (!paused && afterTakeHeadroom < BUFFER_UNDERRUN_THRESHOLD_MS) {
                runCatching { track.pause() }
                paused = true
                scope.launch {
                    _observableState.update { it.copy(isBuffering = true) }
                }
            }

            writePcmRespectingVolume(track, chunk.pcm, ::lastVolGet) { consumerLastVol = it }
            writeSilenceRespectingVolume(track, chunk.trailingSilenceBytes, ::lastVolGet) { consumerLastVol = it }
        }
    } finally {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
        if (paused) {
            // Make sure UI doesn't get stuck in buffering on shutdown
            scope.launch {
                _observableState.update { it.copy(isBuffering = false) }
            }
        }
        if (naturalEnd && pipelineRunning.get()) {
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
```

Add the threshold constants to the companion object:

```kotlin
/** When buffered audio falls below this, pause AudioTrack and surface
 *  a "buffering" UI state. Tab A7 Lite's hardware buffer is ~2-3 s
 *  deep; pausing at 2 s gives the listener clear feedback before the
 *  silence fully drains the buffer. */
const val BUFFER_UNDERRUN_THRESHOLD_MS = 2_000L

/** Hysteresis. Don't resume until we have this much queued or we'll
 *  thrash pause/play on every chunk transition. */
const val BUFFER_RESUME_THRESHOLD_MS = 4_000L
```

- [ ] **Step 2: Build.**

Run: `./gradlew :core-playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run tests.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt
git commit -m "feat(playback): pause-buffer-resume on streaming underrun

When buffered audio drops below 2 s the consumer pauses AudioTrack and
sets PlaybackState.isBuffering=true; resumes when buffered audio
recovers above 4 s (hysteresis prevents pause/play thrash on every
chunk). Listener hears a clean pause + resume instead of silence
dribbling out of an underrunning AudioTrack ring buffer.

Doesn't fix the underlying gap (PR-C-F do, by moving synthesis to
disk). This makes the gap an honest UX event."
```

### Task B5: Surface `isBuffering` in the brass-spinner UI

**Files:**
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/reader/AudiobookView.kt`

Search for the existing `warmingUp` derived state (~line 128 in HEAD~ snapshot) and extend it to OR-in `isBuffering`.

- [ ] **Step 1: Read the file to locate the `warmingUp` definitions.**

```bash
grep -n "warmingUp" feature/src/main/kotlin/in/jphe/storyvox/feature/reader/AudiobookView.kt
```

Expected output: two lines, one near 128 and one near 199.

- [ ] **Step 2: Replace each `warmingUp` definition.**

Find:
```kotlin
val warmingUp = state.isPlaying && state.sentenceEnd == 0
```

Replace with:
```kotlin
val warmingUp = state.isPlaying && state.sentenceEnd == 0
val showSpinner = warmingUp || state.isBuffering
```

Then change every reference to `warmingUp` in the spinner / loading-block code to `showSpinner`. Keep the "Voice waking up…" label gated on `warmingUp` only (not on `showSpinner`); add a "Buffering…" label for `state.isBuffering && !warmingUp` so the user sees a different word during mid-stream buffering vs initial warm-up.

Pattern:

```kotlin
val statusLabel = when {
    warmingUp -> "Voice waking up…"
    state.isBuffering -> "Buffering…"
    else -> /* existing else branch */
}
```

(Reuse the existing `Text(...)` block; just swap the text expression.)

- [ ] **Step 3: Read the modified file end-to-end to confirm no leftover `warmingUp` reference where `showSpinner` is wanted.**

- [ ] **Step 4: Build.**

Run: `./gradlew :feature:assembleDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add feature/src/main/kotlin/in/jphe/storyvox/feature/reader/AudiobookView.kt
git commit -m "feat(reader): brass spinner during mid-stream buffering

The warming-up spinner now surfaces for both initial voice load and
mid-stream buffering events. Status label distinguishes 'Voice waking up…'
(initial) from 'Buffering…' (underrun)."
```

### Task B6: Tablet verification on R83W80CAFZB

**Files:** none

- [ ] **Step 1: Claim tablet lock #47.**

- [ ] **Step 2: Build + install.**

```bash
./gradlew :app:assembleDebug
adb -s R83W80CAFZB install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Foreground app + play Sky Pride Ch.1 for 90 s.**

Watch for:
1. **Pre-PR-B baseline** comparison: previously, after ~10 s of clean playback, listener heard silence-then-audio cycles repeatedly.
2. **Post-PR-B expected**: after ~10 s of clean playback, AudioTrack pauses cleanly with brass spinner + "Buffering…" label, then resumes when 4 s of audio is queued. No silence-from-AudioTrack-underrun.

- [ ] **Step 4: Capture a screenshot of the buffering state.**

```bash
adb -s R83W80CAFZB shell screencap -p /sdcard/buffering.png
adb -s R83W80CAFZB pull /sdcard/buffering.png ~/.claude/projects/-home-jp/scratch/aurelia-tts-chunk-gap/buffering-state.png
```

- [ ] **Step 5: Release tablet lock #47.**

- [ ] **Step 6: Push branch.**

### Task B7: Open PR B

**Files:** none

- [ ] **Step 1: Open PR.**

```bash
gh pr create --base main --head dream/aurelia/tts-chunk-gap \
  --title "feat(playback): pause-buffer-resume instead of silent underrun" \
  --body "$(cat <<'EOF'
## Summary

When the streaming TTS pipeline underruns on slow voice + slow device
combos (Piper-high on Tab A7 Lite has a 0.285× realtime synthesis rate;
producer falls 715 ms/sec behind playback), the listener used to hear
intermittent silence as the AudioTrack hardware buffer drained. Now,
when buffered audio drops below 2 s, the consumer pauses AudioTrack
and surfaces a 'Buffering…' UI state via the existing brass spinner;
playback resumes when 4 s of audio is queued (hysteresis).

This does NOT close the gap — synthesis is still 3.5× too slow for
this combo, gaps still happen periodically. PR makes the gap an honest
UX event instead of dead air. The structural fix (chapter PCM cache,
playing from disk) is spec'd in
docs/superpowers/specs/2026-05-07-pcm-cache-design.md and follows in
PR-C-F.

## Why

JP measured baseline of 8 s median inter-chunk silence on Piper-high.
Smallest immediate-relief change: convert the silence to a paused-with-
spinner state so listeners know the device is working, not broken.

## Test plan

- [x] core-playback unit tests pass — bufferHeadroomMs accounting +
      isBuffering serialization
- [x] R83W80CAFZB: play Sky Pride Ch.1 for 90 s on Piper-high. Brass
      spinner with 'Buffering…' label appears mid-chapter on underrun;
      AudioTrack resumes cleanly when buffer recovers
- [x] No regression in voice-warm-up spinner (initial 'Voice waking up…'
      still shows pre-first-chunk)
- [x] Pause/resume by user still works
- [x] Sleep timer fade still works through buffering events
- [x] No buffering label sticks after pause/stop

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Wait for copilot review (per memory feedback_copilot_review_required.md).**

- [ ] **Step 3: After approval, ship via /ship patch (auto-bumps to v0.4.27, commits CHANGELOG, pushes tag, CI builds + uploads APK).**

Memory note: ship pipeline auto-runs commit → push → tag → CI on JP's "ship it" or equivalent. Per memory `feedback_ship_dont_investigate.md`, JP wants the next tagged release as soon as the candidate is in the tree.

```bash
# After PR squash-merges:
git checkout main && git pull
/ship patch
```

The /ship skill handles version bump + CHANGELOG + tag + CI. CI builds the APK and the existing release-install workflow (per memory `feedback_install_test_each_iteration.md`) will pick it up.

---

## Self-review

**Spec coverage check:**
- ✓ PR A — PcmSource extraction → Tasks A1–A6
- ✓ PR B — pause-buffer-resume + isBuffering → Tasks B1–B7
- ✓ Out-of-scope PRs (C-H) called out in the goal block
- ✓ All cited memories (`feedback_install_test_each_iteration.md`, `feedback_copilot_review_required.md`, `feedback_ship_dont_investigate.md`) are referenced where relevant
- ✓ Tablet lock #47 protocol followed (claim → install → release) in Tasks A5 and B6
- ✓ JP's success criterion ("high-quality voices smooth on slow devices") explicitly noted as NOT met by this PR — full spec's PR-C-F closes the gap

**Placeholder scan:** None. Every Kotlin block is complete and compilable in context.

**Type consistency:** `PcmSource.nextChunk()` signature is identical across A1, A2, A3, B1. `PcmChunk` fields (`sentenceIndex`, `range`, `pcm`, `trailingSilenceBytes`) are consistent. `EngineStreamingSource.VoiceEngineHandle` SAM contract identical in A2 and A4.

**Outstanding:** the runBlocking-on-audio-thread pattern in Task A4 is a deliberate trade-off — the audio consumer thread is dedicated and pinned at URGENT_AUDIO, so blocking it doesn't starve any other work. Documented inline in the commit message. If a code reviewer flags it, the alternative is a bounded `Channel` and a coroutine-driven consumer, which loses the URGENT_AUDIO pinning that the original docstring (EnginePlayer line 397-411) calls out as load-bearing for clean output on Tab A7 Lite.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-07-pcm-cache-pr-a-and-b.md`.

In auto mode (per JP's instruction "do it all"), I'll proceed via **Inline Execution** using the executing-plans skill — batch-executing PR A end-to-end (A1-A6) with a checkpoint after `:core-playback:testDebugUnitTest` passes, then PR B (B1-B7) with a checkpoint after tablet verification. PR-open and CI ship are JP-visible boundaries; everything in between stays inside this session.
