# PCM Cache PR-C Implementation Plan — Filesystem Layer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the **filesystem layer** of the chapter PCM cache spec'd in `docs/superpowers/specs/2026-05-07-pcm-cache-design.md`. Just storage + index + LRU eviction. Read/write surface that future PRs (D: streaming-tee writer, E: cache-hit playback) plug into. **Zero integration with `EnginePlayer` in this PR** — nothing in the playback path consults the cache yet.

**Architecture:** Three new public types in a new `core-playback/.../playback/cache/` package:

- `PcmCacheKey` — pure data class. Five-field stable identity (chapter / voice / speed / pitch / chunkerVersion) hashed to a SHA-256 file basename.
- `PcmCache` — `@Singleton` filesystem store rooted at `${context.cacheDir}/pcm-cache/`. Owns the on-disk layout (`<sha>.pcm`, `<sha>.idx.json`, `<sha>.meta.json`), exposes `isComplete`, `fileFor`, `indexFor`, `appender`, plus `evictTo(quotaBytes, pinnedKeys)` and `delete*` helpers.
- `PcmAppender` — streaming writer. `appendSentence(sentence, pcm)` accumulates bytes + builds the in-memory sentence index; `finalize()` writes the sidecar `.idx.json` (its presence is the "cache complete" marker); `abandon()` deletes the partial files.

A `PcmCacheConfig` thin wrapper exposes the user-facing quota knob (DataStore-backed, defaults to **2 GB** per spec). PR-G adds the Settings UI; PR-C just provides a hard-coded default flow + override hook so the rest of the layer can read it. No DI cycles.

`SentenceChunker` gains a `CHUNKER_VERSION = 1` constant — included in the cache key so future boundary changes invalidate stale caches without DB migration.

**Tech stack:** Kotlin, kotlinx.serialization for the JSON sidecars, Hilt singletons, JUnit4 + Robolectric (already on core-playback's test classpath via `VoiceManagerTest`). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` (sections "PcmCacheKey", "PcmCache", "Storage policy", and the "Files on disk" detail under PcmCache). Plus the "PR C — `PcmCache` + `PcmCacheKey` + filesystem layout. Just the storage layer + tests. No integration yet." line in the Implementation outline.

**Out of scope (explicitly deferred):**

- **PR-D (streaming tee).** `EngineStreamingSource` does NOT call `PcmCache.appender()` in this PR. The appender exists, has tests, but no production caller.
- **PR-E (cache hit path).** `EnginePlayer.loadAndPlay` does NOT consult `PcmCache.isComplete()` in this PR.
- **PR-F (RenderScheduler / WorkManager).** No worker, no `addToLibrary` hook, no chapter-N+2 trigger.
- **PR-G (Settings UI).** No quota selector in Settings → Playback. The quota is a DataStore preference with a default (2 GB), readable, but no UI to change it.
- **PR-H (per-chapter status icon, Clear Cache button).** Polish.
- **`CacheFileSource`.** A second `PcmSource` impl is not added in this PR — that's PR-E. The `.pcm` file format we write here MUST be raw 16-bit signed mono PCM at the engine sample rate so PR-E's mmap reader is a straight `RandomAccessFile.channel.map`.
- **Feature flag.** Spec calls for a `pcm-cache-enabled` marker file; that gates the integration in PR-D/E/F. PR-C's classes can ship un-flagged because nothing constructs them yet outside tests.
- **Storage budget == 1 GB or 2 GB?** Spec says 2 GB default; pinning that here. Settings UI in PR-G can override.

---

## File Structure

### New files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKey.kt`
  Five-field data class + SHA-256 basename fn.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheManifest.kt`
  Two `@Serializable` data classes: `PcmIndex` (the `.idx.json` shape) + `PcmMeta` (the `.meta.json` shape) + a small JSON instance.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheConfig.kt`
  DataStore-backed `quotaBytes` flow with a 2 GB default. PR-G replaces this with a Settings-UI-driven impl.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCache.kt`
  Singleton, the public face. Read/write API + LRU eviction.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmAppender.kt`
  Streaming writer for one chapter render. Owned-and-returned by `PcmCache.appender(key)`.

### Modified files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/SentenceChunker.kt`
  Add a top-level `const val CHUNKER_VERSION = 1`. Pure constant, no behavior change.
- `core-playback/build.gradle.kts`
  No changes needed — kotlinx.serialization plugin + json runtime are already on the classpath (via PlaybackState's `@Serializable`). Robolectric already present for tests.

### New test files

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKeyTest.kt`
  SHA-256 hash stability across runs, distinct keys → distinct hashes, every field contributes to the hash.
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmAppenderTest.kt`
  Append two sentences, finalize, read back: PCM file = concatenation; index byte_offsets/byte_lens correct; meta.json present; `abandon()` deletes everything.
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheTest.kt`
  Robolectric-backed (uses `ApplicationProvider.getApplicationContext()` for a real `cacheDir`). Round-trip: write → finalize → `isComplete` true; LRU eviction; `deleteAllForChapter`; `totalSizeBytes` accuracy; pinned keys never evicted.

---

## Conventions

- All commits use conventional-commit style: `feat`, `fix`, `refactor`, `docs`, `test`. Branch is `dream/aurora/pcm-cache-pr-c`.
- Run from the worktree root: `/home/jp/Projects/storyvox-worktrees/aurora-pcm-cache-prc`.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest` (Robolectric tests run on JVM, no emulator needed).
- Full-app build sanity: `./gradlew :app:assembleDebug`.
- Selective `git add` only — no `-A` / `.` per CLAUDE.md.
- **No version bump** in this PR. Orchestrator handles release bundling.
- **No tablet install** in this PR. Orchestrator handles. The cache layer has no observable runtime effect until PR-D wires it in, so device verification is not load-bearing for PR-C.

---

## Sub-change sequencing

PR-C is one PR but ships in five small commits so review surface stays narrow. Each commit compiles and passes its own tests; the PR is the union.

1. `feat(playback): SentenceChunker.CHUNKER_VERSION constant` — single-line guard for cache invalidation when sentence boundaries change.
2. `feat(playback): PcmCacheKey + manifest types` — pure data + JSON.
3. `feat(playback): PcmAppender streaming writer` — file + index assembly, finalize/abandon.
4. `feat(playback): PcmCache singleton with LRU eviction` — read/write API, eviction policy, DataStore-backed quota.
5. `test(playback): PcmCache + PcmAppender round-trip + eviction tests` — Robolectric-backed integration.

---

## PR-C Tasks

### Task C1: Add `CHUNKER_VERSION` constant

**Files:** `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/SentenceChunker.kt`

Cache keys must invalidate when sentence boundaries change between releases — otherwise a sidecar index built at chunker version N is wrong for a chapter chunked at version N+1. We bake a single integer constant into the chunker file and include it in `PcmCacheKey`. No production caller bumps it now; the next `SentenceChunker` change that alters boundaries will bump it from 1 to 2 and stale caches self-evict on next read.

- [ ] **Step 1: Add the constant.**

In `SentenceChunker.kt`, above the `data class Sentence(...)` declaration:

```kotlin
/**
 * Bumped when [SentenceChunker.chunk] changes its boundary semantics in a way
 * that would shift `(startChar, endChar)` for the same input. Included in
 * [`in`.jphe.storyvox.playback.cache.PcmCacheKey] so a chunker change
 * self-evicts stale on-disk PCM caches without a DB migration. Bumping
 * leaves old caches orphaned on disk; LRU eviction reclaims them.
 *
 * Bump policy: increment by 1 in the same commit that changes [chunk]'s
 * output. Do NOT bump for cosmetic refactors that don't move any
 * `(startChar, endChar)` pair on real input. If unsure, write a quick
 * test with a representative chapter and diff the resulting Sentence
 * lists.
 */
const val CHUNKER_VERSION: Int = 1
```

- [ ] **Step 2: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/SentenceChunker.kt
git commit -m "feat(playback): SentenceChunker.CHUNKER_VERSION for cache invalidation

Single integer constant included in PcmCacheKey so future chunker boundary
changes self-evict stale on-disk PCM caches without a DB migration.
Bump policy lives in the kdoc."
```

---

### Task C2: `PcmCacheKey` + manifest types

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKey.kt`
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheManifest.kt`
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKeyTest.kt`

#### C2.1: `PcmCacheKey`

```kotlin
package `in`.jphe.storyvox.playback.cache

import java.security.MessageDigest

/**
 * Identity of one cached chapter render. Five fields because the rendered
 * audio depends on every one:
 *  - [chapterId] — different chapters obviously different audio.
 *  - [voiceId] — same chapter under "cori" vs "amy" sounds different.
 *  - [speedHundredths] — `currentSpeed × 100`, rounded. Quantizing to
 *    hundredths means 1.00× and 1.02× share a key; 1.00× and 1.25× don't.
 *    The spec accepts this finite-set tradeoff (most users settle on
 *    1.0/1.25/1.5/1.75/2.0×, ≤ 5 distinct keys per chapter+voice).
 *  - [pitchHundredths] — same quantization story for pitch.
 *  - [chunkerVersion] — see [`in`.jphe.storyvox.playback.tts.CHUNKER_VERSION].
 *    A chunker change shifts sentence boundaries which makes the sidecar
 *    index wrong; bumping this invalidates without DB migration.
 *
 * `fileBaseName()` is a 64-char SHA-256 of `toString()`. Stable across
 * runs (no salt, no time component); a key derived twice with the same
 * fields hashes to the same string. The base name is opaque on disk
 * — never decoded back. Listing files in `pcm-cache/` and matching
 * basenames to known keys is a tombstone-driven sweep that lives in
 * `PcmCache`, not here.
 */
data class PcmCacheKey(
    val chapterId: String,
    val voiceId: String,
    val speedHundredths: Int,
    val pitchHundredths: Int,
    val chunkerVersion: Int,
) {
    /**
     * 64-char hex SHA-256 of `toString()`. Used as the on-disk basename
     * (`<sha>.pcm`, `<sha>.idx.json`, `<sha>.meta.json`).
     *
     * Deterministic — same key → same basename across runs and devices.
     * The trailing `\n` separator in the digest input is paranoia; it
     * keeps a key that happens to embed a `,` in chapterId from
     * colliding with a sibling key whose voiceId starts with `,`. The
     * built-in Kotlin data-class `toString` already separates fields
     * with `, ` so this is belt-and-suspenders, not load-bearing.
     */
    fun fileBaseName(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Quantize a Float speed/pitch (`currentSpeed × 100`, rounded). */
        fun quantize(value: Float): Int = Math.round(value * 100f)
    }
}
```

#### C2.2: `PcmCacheManifest`

```kotlin
package `in`.jphe.storyvox.playback.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `<sha>.idx.json` sidecar. Written by [PcmAppender.finalize] —
 * its presence on disk = "cache is complete". `PcmCache.isComplete`
 * is just `indexFor(key).exists()`.
 *
 * `start`/`end` are character offsets into the chapter's plaintext, so
 * `SentenceTracker` keeps working unchanged; `byteOffset`/`byteLen`
 * locate the sentence's PCM in the `.pcm` file.
 *
 * `trailingSilenceMs` is the punctuation-driven cadence pause the
 * streaming source splices in — preserved here so PR-E's
 * `CacheFileSource` can replay the same cadence.
 */
@Serializable
data class PcmIndex(
    val sampleRate: Int,
    val sentenceCount: Int,
    val totalBytes: Long,
    val sentences: List<PcmIndexEntry>,
)

@Serializable
data class PcmIndexEntry(
    val i: Int,
    val start: Int,
    val end: Int,
    val byteOffset: Long,
    val byteLen: Int,
    val trailingSilenceMs: Int,
)

/**
 * The `<sha>.meta.json` sidecar. Written at the START of a render so an
 * in-progress (uncompleted) render is identifiable: meta exists, .pcm
 * exists, but .idx.json doesn't yet. Lets PR-D's tee writer + PR-F's
 * worker tell "I'm resuming someone else's incomplete render" from
 * "the cache is complete and ready to read".
 *
 * `createdEpochMs` is informational; LRU eviction uses file mtime on
 * the `.pcm` itself, which gets touched on every play.
 */
@Serializable
data class PcmMeta(
    val voiceId: String,
    val sampleRate: Int,
    val createdEpochMs: Long,
    val chunkerVersion: Int,
    val speedHundredths: Int,
    val pitchHundredths: Int,
)

internal val pcmCacheJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true   // forward-compatible with PR-D/E/F additions
    explicitNulls = false
}
```

#### C2.3: `PcmCacheKeyTest`

```kotlin
package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PcmCacheKeyTest {

    private val baseline = PcmCacheKey(
        chapterId = "skypride/ch1",
        voiceId = "cori",
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = CHUNKER_VERSION,
    )

    @Test
    fun `fileBaseName is 64-char hex`() {
        val s = baseline.fileBaseName()
        assertEquals(64, s.length)
        assert(s.all { it in '0'..'9' || it in 'a'..'f' }) {
            "expected lowercase hex, got '$s'"
        }
    }

    @Test
    fun `fileBaseName is stable for the same key`() {
        assertEquals(baseline.fileBaseName(), baseline.copy().fileBaseName())
    }

    @Test
    fun `every field contributes to the hash`() {
        val differentEachField = listOf(
            baseline.copy(chapterId = "skypride/ch2"),
            baseline.copy(voiceId = "amy"),
            baseline.copy(speedHundredths = 125),
            baseline.copy(pitchHundredths = 105),
            baseline.copy(chunkerVersion = baseline.chunkerVersion + 1),
        )
        for (k in differentEachField) {
            assertNotEquals(
                "key changing ${k.toString()} must change the hash",
                baseline.fileBaseName(),
                k.fileBaseName(),
            )
        }
    }

    @Test
    fun `quantize rounds to nearest hundredth`() {
        assertEquals(100, PcmCacheKey.quantize(1.0f))
        assertEquals(125, PcmCacheKey.quantize(1.25f))
        assertEquals(102, PcmCacheKey.quantize(1.024f))   // rounds up
        assertEquals(102, PcmCacheKey.quantize(1.018f))   // rounds up
        assertEquals(101, PcmCacheKey.quantize(1.014f))   // rounds down
        assertEquals(50,  PcmCacheKey.quantize(0.5f))
    }
}
```

- [ ] **Step 1: Create both source files.**
- [ ] **Step 2: Create the test file.**
- [ ] **Step 3: Compile + run tests.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*PcmCacheKeyTest*"`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKey.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheManifest.kt \
        core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheKeyTest.kt
git commit -m "feat(playback): PcmCacheKey + manifest types

Five-field cache identity (chapter, voice, speed, pitch, chunkerVersion)
→ stable SHA-256 basename. Two @Serializable manifests for the on-disk
sidecars: PcmIndex (the per-sentence byte-offset map, written at finalize
time) and PcmMeta (voice + sample rate + timestamps, written at render
start so in-progress renders are identifiable).

Per-field hash test ensures swapping any single field shifts the basename
— prevents a future field rename from accidentally collapsing two keys
that should be distinct."
```

---

### Task C3: `PcmAppender` streaming writer

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmAppender.kt`

The appender is the only thing PR-D will call (`tee.appendSentence(...)` in `EngineStreamingSource`'s producer loop). PR-F's `ChapterRenderJob` also calls it — same shape. So this class is the pure-FS write surface.

Three states the appender lifecycle goes through:

| state | files on disk | how to enter |
|---|---|---|
| **Open** | `<sha>.pcm` (growing), `<sha>.meta.json` (constant) | `PcmCache.appender(key)` constructs |
| **Finalized** | `<sha>.pcm`, `<sha>.meta.json`, `<sha>.idx.json` | `appender.finalize()` |
| **Abandoned** | (none — all three deleted) | `appender.abandon()` |

`isComplete(key)` in `PcmCache` is just `indexFor(key).exists()`. The presence of `.idx.json` is the atomic "this cache entry is whole" signal.

```kotlin
package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.encodeToString

/**
 * Streaming writer for one chapter's PCM cache entry.
 *
 * Returned by [PcmCache.appender]. The caller (PR-D's
 * `EngineStreamingSource` tee, or PR-F's `ChapterRenderJob`) calls
 * [appendSentence] for each generated sentence in order. On natural
 * end-of-chapter the caller calls [finalize] to write the index sidecar
 * and mark the cache complete. On cancellation (user pause + don't
 * resume, voice swap, seek away) the caller calls [abandon] to delete
 * the partial files.
 *
 * **Not thread-safe.** The producer holds the only reference; concurrent
 * `appendSentence` calls would corrupt byte offsets in [sentences].
 *
 * **No partial-finalize.** If the process is killed mid-render, the
 * `.pcm` and `.meta.json` survive but `.idx.json` doesn't — on next
 * boot the entry is "in progress" (PR-D recognizes this by `meta exists
 * + idx missing`). PR-D's resume policy will be "abandon, restart" since
 * the byte offsets we'd have written aren't on disk. PR-C provides the
 * mechanism (the missing-idx state); PR-D writes the policy.
 */
class PcmAppender internal constructor(
    private val pcmFile: File,
    private val metaFile: File,
    private val indexFile: File,
    private val sampleRate: Int,
    private val voiceId: String,
    private val chunkerVersion: Int,
    private val speedHundredths: Int,
    private val pitchHundredths: Int,
) {
    /** Per-sentence byte offsets accumulated as we write. Index file is
     *  built from this at finalize time. */
    private val sentences = mutableListOf<PcmIndexEntry>()

    /** Running byte count == byte_offset for the next appended sentence. */
    private var totalBytesWritten: Long = 0L

    /** Open append-stream on the pcm file. Held for the appender's
     *  lifetime so we don't reopen + seek for each sentence. */
    private val pcmStream: FileOutputStream

    @Volatile private var closed: Boolean = false

    init {
        // Ensure parent dir exists (PcmCache.appender constructs the
        // entry's basename atomically; this is a defensive mkdir for
        // the case where someone wiped pcm-cache/ between construction
        // and first write).
        pcmFile.parentFile?.mkdirs()
        pcmStream = FileOutputStream(pcmFile, /* append = */ true)

        // Meta is written ONCE at construction. Stays untouched through
        // finalize (presence of idx.json is the completion signal, not
        // any meta mutation).
        if (!metaFile.exists()) {
            val meta = PcmMeta(
                voiceId = voiceId,
                sampleRate = sampleRate,
                createdEpochMs = System.currentTimeMillis(),
                chunkerVersion = chunkerVersion,
                speedHundredths = speedHundredths,
                pitchHundredths = pitchHundredths,
            )
            metaFile.writeText(pcmCacheJson.encodeToString(meta))
        }
        // Pre-existing pcm file means we're resuming an interrupted
        // render. Seek the running counter to the existing length so
        // subsequent appendSentence calls see correct byte_offsets.
        // PR-D's policy decides whether to actually resume vs abandon-
        // and-start-over; this just keeps us self-consistent if a
        // caller does choose to resume.
        totalBytesWritten = pcmFile.length()
    }

    /**
     * Append one sentence's PCM. Records its byte range in the in-memory
     * index. The bytes hit disk synchronously (FileOutputStream.write +
     * flush); we don't buffer because the producer's pace is so slow that
     * any extra latency is irrelevant, and we want the file to be a true
     * record of what's been generated for resume scenarios.
     *
     * @throws IllegalStateException if the appender has been finalized
     *  or abandoned.
     */
    @Throws(IOException::class)
    fun appendSentence(sentence: Sentence, pcm: ByteArray, trailingSilenceMs: Int) {
        check(!closed) { "appender already closed (finalize/abandon)" }
        if (pcm.isEmpty()) return  // engine declined this sentence; skip cleanly

        val byteOffset = totalBytesWritten
        pcmStream.write(pcm)
        pcmStream.flush()
        totalBytesWritten += pcm.size

        sentences += PcmIndexEntry(
            i = sentence.index,
            start = sentence.startChar,
            end = sentence.endChar,
            byteOffset = byteOffset,
            byteLen = pcm.size,
            trailingSilenceMs = trailingSilenceMs,
        )
    }

    /**
     * Mark the cache entry complete: closes the pcm stream and writes
     * the `.idx.json` sidecar atomically (write to `.tmp`, rename).
     *
     * After finalize the appender is closed; further calls throw.
     */
    @Throws(IOException::class)
    fun finalize() {
        check(!closed) { "appender already closed (finalize/abandon)" }
        closed = true
        runCatching { pcmStream.close() }

        val index = PcmIndex(
            sampleRate = sampleRate,
            sentenceCount = sentences.size,
            totalBytes = totalBytesWritten,
            sentences = sentences.toList(),
        )
        // Atomic write — write tmp then rename so a partial idx.json
        // never appears mid-write (a concurrent reader would mistake
        // it for "complete" when it's actually a half-flushed file).
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(pcmCacheJson.encodeToString(index))
        if (!tmp.renameTo(indexFile)) {
            // Fallback: rename can fail on some FS; copy + delete.
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Discard the in-progress entry: closes the stream, deletes the
     * pcm + meta + (any partial) idx files. After abandon the appender
     * is closed; further calls throw.
     */
    fun abandon() {
        if (closed) return
        closed = true
        runCatching { pcmStream.close() }
        runCatching { pcmFile.delete() }
        runCatching { metaFile.delete() }
        runCatching { indexFile.delete() }
        runCatching { File(indexFile.parentFile, indexFile.name + ".tmp").delete() }
    }
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmAppender.kt
git commit -m "feat(playback): PcmAppender streaming writer

Returned by PcmCache.appender(key) (next commit). Three states:
Open (pcm growing, meta on disk) → Finalized (pcm + meta + idx) on
finalize(); → Abandoned (all gone) on abandon(). Atomic finalize via
tmp+rename so a partial idx.json never appears mid-write — presence
of idx.json is the cache-complete signal, must be all-or-nothing.

Tests land alongside PcmCache in the next test commit since they share
a Robolectric-backed setup."
```

---

### Task C4: `PcmCacheConfig` + `PcmCache` singleton

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheConfig.kt`
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCache.kt`

#### C4.1: `PcmCacheConfig`

A thin DataStore wrapper. PR-G replaces this with a Settings-UI-driven flow; PR-C just gives the rest of the cache layer a way to read the quota with a default.

```kotlin
package `in`.jphe.storyvox.playback.cache

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User-facing quota for the PCM cache, persisted via DataStore.
 *
 * **Default: 2 GB** (per spec — sized for ~30 chapters of Piper-high
 * Sky Pride, which is the binge-listening case JP measured).
 *
 * PR-G's Settings UI overwrites this value via
 * [setQuotaBytes]. PR-C only ships the read path with a hard-coded
 * default; if no Settings UI ever lands, the cache simply never grows
 * past 2 GB.
 *
 * The quota is enforced by [PcmCache.evictTo]; the config object
 * itself does no enforcement.
 */
@Singleton
class PcmCacheConfig @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.pcmCacheConfigStore

    suspend fun quotaBytes(): Long =
        store.data.map { it[QUOTA_KEY] ?: DEFAULT_QUOTA_BYTES }.first()

    suspend fun setQuotaBytes(bytes: Long) {
        // Floor at 100 MB so a misconfigured Settings UI doesn't lock
        // the user into a no-cache mode by mistake. "Unlimited" is
        // represented by Long.MAX_VALUE; UI knob in PR-G picks the
        // discrete options.
        val clamped = bytes.coerceAtLeast(100L * 1024 * 1024)
        androidx.datastore.preferences.core.edit(store) {
            it[QUOTA_KEY] = clamped
        }
    }

    private companion object {
        val QUOTA_KEY = longPreferencesKey("pcm_cache_quota_bytes")

        /** 2 GB. Spec calls this out as the default — sized to fit ~30
         *  chapters of Piper-high audiobook PCM. */
        const val DEFAULT_QUOTA_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}

private val Context.pcmCacheConfigStore by preferencesDataStore(name = "pcm_cache_config")
```

(Note: `DataStore.edit { ... }` is a `suspend` extension; the import is from `androidx.datastore.preferences.core.edit`. Inlining the qualified name keeps the import list minimal in a small file.)

#### C4.2: `PcmCache`

```kotlin
package `in`.jphe.storyvox.playback.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filesystem-backed PCM cache. Owns `${context.cacheDir}/pcm-cache/`
 * and the `<sha>.pcm` / `<sha>.idx.json` / `<sha>.meta.json` triple
 * per cached chapter.
 *
 * The cache root lives in [Context.getCacheDir] (vs `filesDir`) on
 * purpose: Android may evict `cacheDir` under storage pressure even
 * without our help, and our LRU is conservative on top of that. A
 * surprise OS-level wipe is recoverable — next play re-renders. If
 * we'd put the cache in `filesDir` we'd be promising durability we
 * don't actually want.
 *
 * Concurrency:
 *  - Reads ([isComplete], [fileFor], [indexFor], [totalSizeBytes]) are
 *    safe to call from any thread.
 *  - Writes ([appender], [evictTo], [delete*]) are also thread-safe
 *    individually, but at most one [PcmAppender] should be open per
 *    key at a time. PR-D enforces that mutual exclusion at the
 *    streaming-source / render-job boundary; PR-C trusts the caller.
 *
 * No integration in this PR — `EnginePlayer` doesn't reference this
 * class. PR-D wires the `appender` into `EngineStreamingSource`'s
 * producer loop; PR-E adds a `CacheFileSource` that reads via
 * `fileFor` + `indexFor`.
 */
@Singleton
class PcmCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: PcmCacheConfig,
) {
    private val rootDir: File by lazy {
        File(context.cacheDir, ROOT_DIR_NAME).apply { mkdirs() }
    }

    /** Root directory exposed for tests + the future "Clear cache" UI. */
    fun rootDirectory(): File = rootDir

    fun pcmFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$PCM_SUFFIX")

    fun indexFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$INDEX_SUFFIX")

    fun metaFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$META_SUFFIX")

    /** True iff the index sidecar exists (i.e. a finalized render
     *  landed for this key). Cheap — single `File.exists` syscall. */
    fun isComplete(key: PcmCacheKey): Boolean = indexFileFor(key).exists()

    /**
     * Open a writer for [key]. If a previous render for the same key
     * was abandoned, leftover `.pcm` / `.meta.json` are NOT auto-deleted
     * here (the appender's `init` block treats existing pcm length as
     * the resume offset). PR-D decides resume vs restart; if it picks
     * restart, it should call `abandonExisting(key)` first.
     */
    fun appender(
        key: PcmCacheKey,
        sampleRate: Int,
    ): PcmAppender = PcmAppender(
        pcmFile = pcmFileFor(key),
        metaFile = metaFileFor(key),
        indexFile = indexFileFor(key),
        sampleRate = sampleRate,
        voiceId = key.voiceId,
        chunkerVersion = key.chunkerVersion,
        speedHundredths = key.speedHundredths,
        pitchHundredths = key.pitchHundredths,
    )

    /** Touch the pcm file's mtime — call on every successful play of
     *  a cached chapter so LRU eviction prefers genuinely-cold entries. */
    suspend fun touch(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        pcmFileFor(key).setLastModified(System.currentTimeMillis())
        Unit
    }

    /** Delete pcm + idx + meta for [key]. Idempotent. */
    suspend fun delete(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        runCatching { pcmFileFor(key).delete() }
        runCatching { indexFileFor(key).delete() }
        runCatching { metaFileFor(key).delete() }
        runCatching { File(rootDir, "${key.fileBaseName()}$INDEX_SUFFIX$TMP_SUFFIX").delete() }
        Unit
    }

    /**
     * Delete every cached entry whose `<sha>.meta.json` references
     * [chapterId]. Used when chapter text changes (re-imported, edited
     * in source) — the byte offsets in the index are wrong for the new
     * text, so all voice variants must go.
     *
     * Note: this works because [PcmMeta] doesn't currently store
     * chapterId. We DO need to store it. **Update meta to include
     * chapterId in this PR** (the meta is forward-compatible — `.meta.json`
     * is read with `ignoreUnknownKeys`, and we add the field cleanly).
     */
    suspend fun deleteAllForChapter(chapterId: String) = withContext(Dispatchers.IO) {
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) } ?: return@withContext
        for (mf in metaFiles) {
            val basename = mf.name.removeSuffix(META_SUFFIX)
            val meta = runCatching {
                kotlinx.serialization.json.Json.Default
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            if (meta.chapterId == chapterId) {
                runCatching { File(rootDir, "$basename$PCM_SUFFIX").delete() }
                runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
                runCatching { mf.delete() }
            }
        }
    }

    /** Sum of `.pcm` file sizes under the cache root (idx + meta are
     *  trivially small; spec budget is dominated by audio). */
    suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .sumOf { it.length() }
    }

    /**
     * LRU-evict (by .pcm mtime, oldest first) until total .pcm size ≤
     * [quotaBytes]. Entries whose key SHA matches [pinnedBasenames] are
     * skipped — caller passes basenames for currently-playing + next-
     * in-sequence chapters per spec.
     *
     * Returns the number of entries evicted.
     */
    suspend fun evictTo(
        quotaBytes: Long,
        pinnedBasenames: Set<String> = emptySet(),
    ): Int = withContext(Dispatchers.IO) {
        var total = totalSizeBytes()
        if (total <= quotaBytes) return@withContext 0

        // Sort by mtime ascending — oldest first. Skip pinned.
        val candidates = (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .filter { it.name.removeSuffix(PCM_SUFFIX) !in pinnedBasenames }
            .sortedBy { it.lastModified() }

        var evicted = 0
        for (pcm in candidates) {
            if (total <= quotaBytes) break
            val basename = pcm.name.removeSuffix(PCM_SUFFIX)
            val sz = pcm.length()
            runCatching { pcm.delete() }
            runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
            runCatching { File(rootDir, "$basename$META_SUFFIX").delete() }
            total -= sz
            evicted++
        }
        evicted
    }

    /** Convenience overload — reads quota from [PcmCacheConfig]. */
    suspend fun evictToQuota(pinnedBasenames: Set<String> = emptySet()): Int =
        evictTo(config.quotaBytes(), pinnedBasenames)

    /** Wipe everything under the cache root. PR-G's "Clear cache" button. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { runCatching { it.delete() } }
        Unit
    }

    private companion object {
        const val ROOT_DIR_NAME = "pcm-cache"
        const val PCM_SUFFIX = ".pcm"
        const val INDEX_SUFFIX = ".idx.json"
        const val META_SUFFIX = ".meta.json"
        const val TMP_SUFFIX = ".tmp"
    }
}
```

**Note on `deleteAllForChapter`:** the spec's `PcmMeta` doesn't currently include `chapterId`. We need it for chapter-scoped invalidation. Add `chapterId: String` as a field to `PcmMeta` in the manifest file (next step), update `PcmAppender` constructor to take + write it, update `PcmCache.appender` to forward it. The plan above already references the field; cross-update before implementing.

Concretely:

- **Add** `val chapterId: String,` as the first field of `PcmMeta` in `PcmCacheManifest.kt`.
- **Add** `chapterId: String,` as a parameter to `PcmAppender`'s constructor (between `voiceId` and `chunkerVersion` to read naturally).
- **Set** `chapterId = chapterId` when constructing `PcmMeta` in `PcmAppender.init`.
- **Forward** `chapterId = key.chapterId` in `PcmCache.appender(...)`.

- [ ] **Step 1: Create `PcmCacheConfig.kt`.**
- [ ] **Step 2: Create `PcmCache.kt`.**
- [ ] **Step 3: Update `PcmCacheManifest.kt` — add `chapterId` field to `PcmMeta`.**
- [ ] **Step 4: Update `PcmAppender.kt` — take `chapterId` constructor param and write it into meta.**
- [ ] **Step 5: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheConfig.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCache.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmCacheManifest.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmAppender.kt
git commit -m "feat(playback): PcmCache singleton with LRU eviction

Files on disk: \${context.cacheDir}/pcm-cache/<sha>.pcm + .idx.json + .meta.json.
Presence of .idx.json = 'cache complete'. PcmCache exposes isComplete,
fileFor/indexFor/metaFor, appender(key, sampleRate), evictTo(quotaBytes,
pinnedBasenames), deleteAllForChapter, clearAll, totalSizeBytes.

PcmCacheConfig is DataStore-backed quota (default 2 GB per spec); PR-G's
Settings UI replaces the hard-coded read with a flow. Quota floor 100 MB
prevents a misconfigured UI from locking the user out of caching.

deleteAllForChapter required adding chapterId to PcmMeta so we can match
entries by chapter (text-changed invalidation). All-voices-of-this-chapter
go in one sweep.

No integration yet — EnginePlayer doesn't reference this. PR-D wires
appender into the streaming source's tee; PR-E adds CacheFileSource that
reads fileFor+indexFor."
```

---

### Task C5: Round-trip + eviction tests

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmAppenderTest.kt`
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheTest.kt`

#### C5.1: `PcmAppenderTest`

Pure-JVM (no Robolectric needed — uses `Files.createTempDirectory`). Verifies:
1. Append two sentences → finalize → pcm file = concat, idx.json byte_offsets match.
2. Append + abandon → all three files gone.
3. Append + finalize → meta.json contains chapterId + voiceId.
4. Empty PCM (engine refusal) is silently skipped — doesn't add a zero-length sentence to the index.

```kotlin
package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcmAppenderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pcm-appender-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newAppender(basename: String = "abc"): PcmAppender = PcmAppender(
        pcmFile = File(dir, "$basename.pcm"),
        metaFile = File(dir, "$basename.meta.json"),
        indexFile = File(dir, "$basename.idx.json"),
        sampleRate = 22050,
        chapterId = "skypride/ch1",
        voiceId = "cori",
        chunkerVersion = 1,
        speedHundredths = 100,
        pitchHundredths = 100,
    )

    @Test
    fun `append two sentences then finalize produces correct files`() {
        val app = newAppender()
        val s0 = Sentence(0, 0, 10, "First.")
        val s1 = Sentence(1, 11, 20, "Second.")
        val pcm0 = ByteArray(100) { 0x11 }
        val pcm1 = ByteArray(50)  { 0x22 }

        app.appendSentence(s0, pcm0, trailingSilenceMs = 350)
        app.appendSentence(s1, pcm1, trailingSilenceMs = 350)
        app.finalize()

        // pcm = concat of both
        val pcmRead = File(dir, "abc.pcm").readBytes()
        assertEquals(150, pcmRead.size)
        assertArrayEquals(pcm0, pcmRead.copyOfRange(0, 100))
        assertArrayEquals(pcm1, pcmRead.copyOfRange(100, 150))

        // idx.json present + correct
        val idxText = File(dir, "abc.idx.json").readText()
        val idx = pcmCacheJson.decodeFromString<PcmIndex>(idxText)
        assertEquals(22050, idx.sampleRate)
        assertEquals(2, idx.sentenceCount)
        assertEquals(150L, idx.totalBytes)
        assertEquals(0L, idx.sentences[0].byteOffset)
        assertEquals(100, idx.sentences[0].byteLen)
        assertEquals(100L, idx.sentences[1].byteOffset)
        assertEquals(50, idx.sentences[1].byteLen)
        assertEquals(0, idx.sentences[0].start)
        assertEquals(11, idx.sentences[1].start)

        // meta.json present + carries chapterId
        val metaText = File(dir, "abc.meta.json").readText()
        val meta = pcmCacheJson.decodeFromString<PcmMeta>(metaText)
        assertEquals("skypride/ch1", meta.chapterId)
        assertEquals("cori", meta.voiceId)
        assertEquals(22050, meta.sampleRate)
        assertEquals(1, meta.chunkerVersion)
    }

    @Test
    fun `abandon deletes all three files`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(20), trailingSilenceMs = 350)
        // pcm + meta exist after construction+append; idx will not yet
        assertTrue(File(dir, "abc.pcm").exists())
        assertTrue(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())

        app.abandon()

        assertFalse(File(dir, "abc.pcm").exists())
        assertFalse(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())
    }

    @Test
    fun `empty pcm is skipped silently`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(0), trailingSilenceMs = 350)
        app.appendSentence(Sentence(1, 11, 20, "There."), ByteArray(40) { 0x33 }, trailingSilenceMs = 350)
        app.finalize()

        val idx = pcmCacheJson.decodeFromString<PcmIndex>(File(dir, "abc.idx.json").readText())
        // Only the 40-byte sentence made it
        assertEquals(1, idx.sentenceCount)
        assertEquals(40L, idx.totalBytes)
        assertEquals(1, idx.sentences[0].i)
    }

    @Test
    fun `finalize after abandon throws`() {
        val app = newAppender()
        app.abandon()
        var threw = false
        try { app.finalize() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }
}
```

#### C5.2: `PcmCacheTest` (Robolectric)

```kotlin
package `in`.jphe.storyvox.playback.cache

import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
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
class PcmCacheTest {

    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig

    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        config = PcmCacheConfig(ctx)
        cache = PcmCache(ctx, config)
        // Wipe between tests so leftover entries from one don't poison
        // another's eviction count.
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key1 = PcmCacheKey("ch1", "cori", 100, 100, 1)
    private val key2 = PcmCacheKey("ch2", "cori", 100, 100, 1)
    private val key3 = PcmCacheKey("ch3", "cori", 100, 100, 1)

    private fun renderInto(key: PcmCacheKey, bytes: Int) {
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(
            Sentence(0, 0, 10, "Sentence."),
            ByteArray(bytes) { 0x44 },
            trailingSilenceMs = 350,
        )
        app.finalize()
    }

    @Test
    fun `isComplete is false until finalize lands`() {
        assertFalse(cache.isComplete(key1))
        val app = cache.appender(key1, sampleRate = 22050)
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(50), trailingSilenceMs = 350)
        // pre-finalize: pcm + meta exist, but idx doesn't
        assertFalse(cache.isComplete(key1))
        app.finalize()
        assertTrue(cache.isComplete(key1))
    }

    @Test
    fun `delete removes all three files`() = runBlocking {
        renderInto(key1, bytes = 100)
        assertTrue(cache.isComplete(key1))
        cache.delete(key1)
        assertFalse(cache.isComplete(key1))
        assertFalse(cache.pcmFileFor(key1).exists())
        assertFalse(cache.metaFileFor(key1).exists())
        assertFalse(cache.indexFileFor(key1).exists())
    }

    @Test
    fun `totalSizeBytes sums pcm files only`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        renderInto(key2, bytes = 2_500)
        assertEquals(3_500L, cache.totalSizeBytes())
    }

    @Test
    fun `evictTo removes oldest pcm entries first`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        // Make key1 demonstrably older than key2/key3 so the LRU
        // ordering is unambiguous despite mtime granularity.
        cache.pcmFileFor(key1).setLastModified(System.currentTimeMillis() - 10_000)
        renderInto(key2, bytes = 1_000)
        cache.pcmFileFor(key2).setLastModified(System.currentTimeMillis() - 5_000)
        renderInto(key3, bytes = 1_000)
        cache.pcmFileFor(key3).setLastModified(System.currentTimeMillis())

        // Quota = 2_500, current total = 3_000 → must evict at least 1
        // entry (≥ 500 bytes' worth), and the oldest by mtime is key1.
        val evicted = cache.evictTo(quotaBytes = 2_500L)

        assertTrue("expected at least 1 eviction, got $evicted", evicted >= 1)
        assertFalse("oldest (key1) should be evicted first", cache.isComplete(key1))
        assertTrue("key2 should survive", cache.isComplete(key2))
        assertTrue("key3 should survive", cache.isComplete(key3))
        assertTrue(cache.totalSizeBytes() <= 2_500L)
    }

    @Test
    fun `evictTo skips pinned entries even if they are oldest`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        cache.pcmFileFor(key1).setLastModified(System.currentTimeMillis() - 10_000)
        renderInto(key2, bytes = 1_000)
        cache.pcmFileFor(key2).setLastModified(System.currentTimeMillis() - 5_000)
        renderInto(key3, bytes = 1_000)

        // Pin key1 (the oldest) → eviction must take from key2 instead.
        val evicted = cache.evictTo(
            quotaBytes = 1_500L,
            pinnedBasenames = setOf(key1.fileBaseName()),
        )

        assertTrue(evicted >= 1)
        assertTrue("pinned key1 must survive", cache.isComplete(key1))
    }

    @Test
    fun `deleteAllForChapter removes every voice variant`() = runBlocking {
        val ch1Cori = PcmCacheKey("ch1", "cori", 100, 100, 1)
        val ch1Amy  = PcmCacheKey("ch1", "amy",  100, 100, 1)
        val ch2Cori = PcmCacheKey("ch2", "cori", 100, 100, 1)
        renderInto(ch1Cori, bytes = 100)
        renderInto(ch1Amy,  bytes = 100)
        renderInto(ch2Cori, bytes = 100)

        cache.deleteAllForChapter("ch1")

        assertFalse(cache.isComplete(ch1Cori))
        assertFalse(cache.isComplete(ch1Amy))
        assertTrue("ch2 unaffected", cache.isComplete(ch2Cori))
    }

    @Test
    fun `touch updates pcm mtime`() = runBlocking {
        renderInto(key1, bytes = 100)
        val before = cache.pcmFileFor(key1).lastModified()
        // Drop mtime back so touch's effect is visible.
        cache.pcmFileFor(key1).setLastModified(before - 60_000)
        cache.touch(key1)
        val after = cache.pcmFileFor(key1).lastModified()
        assertTrue("touch must move mtime forward; was $before-60k, now $after",
            after > before - 60_000)
    }

    @Test
    fun `clearAll wipes everything under the root`() = runBlocking {
        renderInto(key1, bytes = 100)
        renderInto(key2, bytes = 100)
        cache.clearAll()
        assertEquals(0, cache.rootDirectory().listFiles()?.size ?: 0)
    }
}
```

- [ ] **Step 1: Create both test files.**
- [ ] **Step 2: Run tests.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*Pcm*Test*"`
Expected: ALL PASS (PcmCacheKeyTest from C2 + PcmAppenderTest + PcmCacheTest).

- [ ] **Step 3: Run the full module test suite to confirm nothing else broke.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Build the full app.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmAppenderTest.kt \
        core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PcmCacheTest.kt
git commit -m "test(playback): PcmAppender + PcmCache round-trip + eviction

PcmAppenderTest is pure-JVM (Files.createTempDirectory). Verifies:
  - append two sentences + finalize → pcm = concat, idx byte_offsets correct
  - meta.json contains chapterId for chapter-scoped invalidation
  - abandon deletes all three files
  - empty pcm (engine refusal) silently skipped
  - finalize after abandon throws

PcmCacheTest uses Robolectric (already on the core-playback test classpath
from VoiceManagerTest #28 work). Verifies:
  - isComplete flips false→true at finalize
  - delete removes pcm + meta + idx
  - totalSizeBytes sums pcm only (idx + meta are negligible)
  - evictTo removes oldest pcm by mtime, respects pinnedBasenames
  - deleteAllForChapter sweeps every voice variant
  - touch updates mtime (used to move actively-played caches to LRU tail)
  - clearAll wipes the root"
```

---

### Task C6: Open PR-C

**Files:** none

- [ ] **Step 1: Push the branch.**

```bash
git push -u origin dream/aurora/pcm-cache-pr-c
```

- [ ] **Step 2: Open the PR.**

```bash
gh pr create --base main --head dream/aurora/pcm-cache-pr-c \
  --title "feat(playback): PCM cache filesystem layer (#86)" \
  --body "$(cat <<'EOF'
## Summary

PR-C of the chapter PCM cache spec'd in
`docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — the structural
fix for slow-voice playback on slow devices (Piper-high at 0.285×
realtime on Tab A7 Lite).

This PR ships the **filesystem layer only**. New types under
`core-playback/.../playback/cache/`:

- `PcmCacheKey` — five-field stable identity (chapter, voice, speed,
  pitch, chunker version) → SHA-256 file basename.
- `PcmCache` — `@Singleton` filesystem store at
  `${cacheDir}/pcm-cache/`. Owns `<sha>.pcm` / `<sha>.idx.json` /
  `<sha>.meta.json`. Exposes `isComplete`, `appender`, `evictTo`,
  `deleteAllForChapter`, `touch`, `clearAll`, `totalSizeBytes`.
- `PcmAppender` — streaming writer, `appendSentence` / `finalize` /
  `abandon`. Atomic finalize via tmp+rename so the index sidecar is
  never seen partially-written.
- `PcmCacheConfig` — DataStore-backed quota knob, default 2 GB per spec.
- `SentenceChunker.CHUNKER_VERSION = 1` — included in the cache key so
  future boundary changes self-evict stale caches.

## Why

Spec section "PR C — `PcmCache` + `PcmCacheKey` + filesystem layout.
Just the storage layer + tests. No integration yet." This is the
load-bearing structural piece — PR-D (streaming-tee) and PR-E
(`CacheFileSource`) plug into the API this PR defines.

## What's NOT in this PR

- **No `EnginePlayer` integration.** Nothing in the playback path
  consults the cache yet. PR-D wires `PcmAppender` into the streaming
  source's tee; PR-E adds `CacheFileSource` reading via `fileFor` +
  `indexFor`.
- **No `RenderScheduler` / WorkManager.** PR-F.
- **No Settings UI.** PR-G — the quota is a DataStore preference
  with a 2 GB default; no quota selector.
- **No `CacheFileSource`.** PR-E.
- **No feature flag toggle.** Cache classes ship un-flagged because
  no production code constructs them outside tests yet.

## Test plan

- [x] `PcmCacheKeyTest` (pure JVM): SHA-256 basename is 64-char hex,
      stable across runs, every field shifts the hash; `quantize`
      rounds correctly.
- [x] `PcmAppenderTest` (pure JVM, `Files.createTempDirectory`):
      append→finalize round-trip, abandon cleanup, empty-PCM skip,
      finalize-after-abandon throws.
- [x] `PcmCacheTest` (Robolectric — already on core-playback's test
      classpath via VoiceManagerTest): `isComplete` lifecycle,
      `delete`/`deleteAllForChapter`, `totalSizeBytes` accuracy, LRU
      eviction with pinning, `touch` mtime, `clearAll`.
- [x] `./gradlew :core-playback:testDebugUnitTest` — all green.
- [x] `./gradlew :app:assembleDebug` — green; the full app still builds
      with the new package even though no production caller references it.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Capture the PR number.**

```bash
PR_NUM=$(gh pr view --json number --jq .number)
```

- [ ] **Step 4: Request Copilot review (per memory feedback_copilot_review_required.md).**

```bash
gh api -X POST repos/jphein/storyvox/pulls/$PR_NUM/requested_reviewers \
  -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
```

- [ ] **Step 5: Capped wait playbook.**

- 5 min: poll `gh pr view $PR_NUM --json reviews,reviewRequests,statusCheckRollup`
- If silent drop (`reviewRequests: []`, `reviews: []`), re-request once
- Another 5 min wait
- 15 min hard cap: if still no review and CI is `SUCCESS` and `mergeStateStatus == CLEAN`, ship on clean CI

- [ ] **Step 6: Address any substantive Copilot findings in fixup commits, push, wait for CI green, squash-merge.**

- [ ] **Step 7: Post-merge — do NOT delete the local branch from this worktree (orchestrator holds main). Remote-side cleanup is fine.**

```bash
git push origin --delete dream/aurora/pcm-cache-pr-c
```

---

## Self-review

**Spec coverage check (PR-C scope from spec line 403):**
- ✓ `PcmCache` (filesystem store + LRU eviction) → C4
- ✓ `PcmCacheKey` (five fields, SHA-256 basename) → C2
- ✓ Filesystem layout (`<sha>.pcm`, `<sha>.idx.json`, `<sha>.meta.json`) → C3, C4
- ✓ Tests for all of the above → C2, C5
- ✓ "No integration yet" — PR-C does not modify `EnginePlayer`, `EngineStreamingSource`, or any playback caller. The streaming-tee write (PR-D) and cache-hit playback (PR-E) explicitly deferred.

**Spec deltas / decisions made in this plan:**
- **`PcmMeta.chapterId` added** — spec's PcmMeta lists voice id, sample rate, sentence count, total bytes, created timestamp; doesn't include chapter id. We need it for `deleteAllForChapter`. Added cleanly via the forward-compatible JSON (`ignoreUnknownKeys = true`).
- **2 GB default quota** — spec lists 2 GB as the default; this plan pins it as `DEFAULT_QUOTA_BYTES = 2L * 1024 * 1024 * 1024` in `PcmCacheConfig`. Settings UI in PR-G can override.
- **Quota floor 100 MB** — spec doesn't specify a floor. Adding one to defend against a misconfigured Settings UI; documented inline.
- **Cache root in `cacheDir`, not `filesDir`** — spec's example uses `${cacheDir}/pcm-cache/` (line 146-148). Using `cacheDir` lets the OS evict under storage pressure, which is what we want for a recoverable cache.
- **`evictTo` takes basenames, not keys** — spec's API takes `pinnedKeys: Set<PcmCacheKey>` but the eviction loop only ever needs to compare basenames. Taking basenames keeps the caller honest about not handing in a key whose hash they haven't computed.

**Placeholder scan:** None — every Kotlin block is complete and compilable in context (modulo the `PcmMeta.chapterId` cross-update called out explicitly in C4).

**Type consistency:** `PcmCacheKey` field order identical across declaration, `quantize` companion fn, and constructor calls in tests. `PcmAppender` constructor parameter order matches across declaration, `PcmCache.appender(...)` forwarding, and `PcmAppenderTest`'s direct construction. `PcmIndex` / `PcmIndexEntry` field names identical between manifest definition and test deserialization.

**Risks deferred to follow-up PRs:**
- Concurrent appender + cache reader on the same key — the spec's "mutual-exclusion contract" lives at the streaming-source / render-job boundary (PR-D + PR-F). PR-C trusts the caller to single-write per key.
- Disk-full mid-render — PR-D/F's `setForeground` worker handles this; PR-C's appender writes synchronously and surfaces `IOException` as-is.

**Outstanding question for orchestrator:** none. Proceeding to implementation.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-c.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute C1-C6 inline. PR-open is the JP-visible boundary; everything before that stays in-session.
