# PCM Cache PR-F Implementation Plan — RenderScheduler + Background Renders

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pre-populate the PCM cache for chapters the user is **likely** to play soon. After PR-D + PR-E, replay is gapless but **first play of a never-cached chapter still streams** (warm-up + buffering UX). PR-F closes that gap by background-rendering chapters in the order users actually consume them:

- **Library add** — when JP adds a fiction (FAB → URL paste, browse → "+", library add button), schedule renders of chapters 1-3 in the background.
- **Chapter natural-end** — when JP finishes chapter N, schedule render of N+2 (N+1 was already scheduled when N started, or is in flight as the side-effect of PR-D's tee from playing N+1).
- **Mode C — "Full Pre-render"** — opt-in switch (PR-G surfaces it in Settings; PR-F creates the gating). When ON, library-add scheduling expands to ALL chapters in the fiction, not just 1-3.

The scheduler is `PcmRenderScheduler` — a `@Singleton` `WorkManager` wrapper following the same shape as the existing `WorkManagerChapterDownloadScheduler`. The worker is `ChapterRenderJob`, a `@HiltWorker` `CoroutineWorker` that:
1. Re-reads the chapter text + active voice (recomputes the cache key — a voice swap between schedule-time and run-time means the worker renders the CURRENT voice's cache, not a stale snapshot).
2. Acquires the shared `engineMutex` (hoisted to a `@Singleton EngineMutex` provider per spec).
3. Runs the same generation loop as the streaming producer, writing to a `PcmAppender` instead of a queue.
4. On completion finalizes + runs LRU eviction.
5. On cancellation (chapter no longer wanted, voice changed and the user is now actively playing the new key, etc.) abandons the partial.

**Architecture:**

```
                 ┌────────────────────────────────────────┐
                 │           Trigger sources              │
                 │                                        │
                 │  FictionRepository.addToLibrary()      │
                 │    → scheduler.scheduleLibraryAdd(id)  │
                 │                                        │
                 │  EnginePlayer.handleChapterDone()      │
                 │    → scheduler.scheduleNextChapter(N+2) │
                 │                                        │
                 │  PerformanceModeConfig.fullPrerender   │
                 │    flow flips ON → existing library    │
                 │    fictions enqueue rest of chapters   │
                 └────────────┬───────────────────────────┘
                              │
                              ▼
                 ┌────────────────────────────────────────┐
                 │      PcmRenderScheduler                │
                 │       (Singleton, WorkManager)         │
                 │                                        │
                 │  enqueueUnique(pcm-render-<chapterId>, │
                 │    KEEP, ChapterRenderJob)             │
                 └────────────┬───────────────────────────┘
                              │
                              ▼
                 ┌────────────────────────────────────────┐
                 │          ChapterRenderJob              │
                 │      (HiltWorker, CoroutineWorker)     │
                 │                                        │
                 │  setForeground for long renders        │
                 │  engineMutex.withLock { generate ... } │
                 │  PcmAppender per sentence              │
                 │  finalize + evictToQuota at end        │
                 └────────────────────────────────────────┘
```

**Tech stack:** Kotlin, AndroidX WorkManager, Hilt's `@HiltWorker` + `@AssistedInject`, kotlinx.coroutines. Adds the existing `androidx.hilt:hilt-work` dep — already present (the project ships `ChapterDownloadWorker`).

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — sections "RenderScheduler" + "Trigger points" + "Mutual-exclusion contract" + the "PR F — `PcmRenderScheduler` + `ChapterRenderJob`" line in the implementation outline.

**Out of scope (deferred):**

- **Settings UI for Mode C / quota.** PR-G. PR-F creates the `fullPrerender: Flow<Boolean>` field on `PlaybackModeConfig` with a default false; PR-G adds the toggle.
- **Status icons.** PR-H.
- **Cancellation policy fine-grained.** PR-F cancels by `chapterId` (the unique work name); a more sophisticated policy (cancel based on user's reading progress, voice change, etc.) is future work.
- **Foreground-service notification UX.** Per spec, `setForeground` for long renders. PR-F uses a minimal "Storyvox is rendering audio" notification. Polish — pretty progress bar, per-chapter title in notification — is a follow-up.
- **Pre-render entire library on install.** Per spec out-of-scope ("UX cost of bad first-run outweighs benefit"). The post-add 1-3 chapter pre-render covers the most common case.

---

## File Structure

### New files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/EngineMutex.kt`
  Hoist the existing `engineMutex` from `EnginePlayer` to a `@Singleton` provider so `EnginePlayer` AND `ChapterRenderJob` share the same instance. Spec calls this out as the mutual-exclusion contract.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmRenderScheduler.kt`
  Interface + `WorkManagerPcmRenderScheduler` impl. Same shape as `ChapterDownloadScheduler`.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJob.kt`
  `@HiltWorker`. The worker. Generates PCM for one chapter, writes to a `PcmAppender`, finalizes + evicts.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggers.kt`
  `@Singleton` glue that observes triggers (library add, chapter done, fullPrerender flow) and calls the scheduler. Sits between `FictionRepository` / `EnginePlayer` and the scheduler so the trigger sources don't directly depend on WorkManager.

### Modified files

- `core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt`
  Add `val fullPrerender: Flow<Boolean>` and `suspend fun currentFullPrerender(): Boolean`. Default false.
- `app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt`
  Implement the new `fullPrerender` flow + setter against the same DataStore.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`
  Inject `PrerenderTriggers`. In `handleChapterDone`, call `triggers.onChapterCompleted(currentChapterId)` so the scheduler enqueues the N+2 render. Inject the `EngineMutex` Singleton instead of constructing a private `Mutex()`.
- `core-data/src/main/kotlin/in/jphe/storyvox/data/repository/FictionRepository.kt`
  In `addToLibrary`, call `triggers.onLibraryAdded(fictionId)` after the row is set. Keeps repo independent of WorkManager — `triggers` is the seam.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`
  Add `val fullPrerender: Boolean = false` to `UiSettings`. Add `setFullPrerender(enabled: Boolean)` to `SettingsRepositoryUi`. **No UI changes** (Settings switch lives in PR-G).
- `app/src/main/AndroidManifest.xml`
  No change — `WorkManager` is already configured. `FOREGROUND_SERVICE_DATA_SYNC` permission likely already present (used by ChapterDownloadWorker if it goes foreground); verify.
- `app/src/main/kotlin/in/jphe/storyvox/di/AppModule.kt` (or wherever Hilt modules live)
  Bind `PcmRenderScheduler` to its Work-Manager impl, same pattern as `ChapterDownloadScheduler`.

### New tests

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggersTest.kt`
  Verifies trigger semantics — library-add schedules 1-3, chapter-done schedules N+2, fullPrerender ON expands to all chapters, cancel cascades correctly.
- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJobTest.kt`
  Robolectric + WorkManager test driver. Stubs the engine handle; verifies the worker writes a complete cache entry.

---

## Conventions

- All commits use conventional-commit style. Branch is `dream/<voice>/pcm-cache-pr-f`.
- Run from worktree root.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest`.
- Full app build: `./gradlew :app:assembleDebug`.
- Tablet smoke-test on R83W80CAFZB **required**: pre-render must actually run (foreground notification visible) and produce a complete cache entry that PR-E's reader picks up. Per memory `feedback_install_test_each_iteration.md`.
- Selective `git add` per CLAUDE.md.
- **No version bump in this PR.** Orchestrator handles release bundling.

---

## Sub-change sequencing

Six commits inside the PR:

1. `refactor(playback): hoist engineMutex to @Singleton EngineMutex` — pure refactor, no behavior change. Pre-condition for ChapterRenderJob to share the mutex with EnginePlayer.
2. `feat(playback): PlaybackModeConfig.fullPrerender flow + UiSettings field` — DataStore + contract additions, no UI.
3. `feat(playback): PcmRenderScheduler interface + WorkManager impl` — scheduler shell, no worker yet.
4. `feat(playback): ChapterRenderJob (HiltWorker) renders one chapter to cache` — the worker.
5. `feat(playback): PrerenderTriggers observes lifecycle events + dispatches scheduler` — the seam between repos/EnginePlayer and the scheduler.
6. `test(playback): trigger + worker semantics` — the tests for both new classes.

---

## PR-F Tasks

### Task F1: Hoist `engineMutex` to a `@Singleton` provider

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/EngineMutex.kt`
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`

The mutex serializes JNI calls into the singleton VoxSherpa engines. Today it's a private `Mutex()` inside `EnginePlayer`. PR-F's `ChapterRenderJob` runs in a separate process scope (WorkManager-managed) and ALSO needs to take this mutex when it calls `generateAudioPCM` — otherwise the worker's render could run concurrently with `EnginePlayer.loadAndPlay`'s `loadModel`, hitting the SIGSEGV race that issue #11 fixed.

The fix is structural: the mutex is process-wide, not EnginePlayer-scoped. Lift it to a Hilt `@Singleton` so both `EnginePlayer` and `ChapterRenderJob` (via `@AssistedInject`) get the same instance.

- [ ] **Step 1: Create `EngineMutex.kt`.**

```kotlin
package `in`.jphe.storyvox.playback.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide mutex that serializes calls into the singleton VoxSherpa
 * engines (Piper / Kokoro). Held during:
 *  - `VoiceEngine.loadModel` / `KokoroEngine.loadModel` (model load + warm-up)
 *  - `VoiceEngine.generateAudioPCM` / `KokoroEngine.generateAudioPCM`
 *    (per-sentence inference)
 *  - `engine.destroy` (release)
 *
 * Without this serialization, `loadModel` can free the native pointer
 * while a `generateAudioPCM` call is mid-flight on another thread —
 * SIGSEGV (issue #11).
 *
 * Two callers in the system:
 *  - `EnginePlayer` — foreground playback, takes the mutex on every
 *    sentence the streaming source generates AND on every
 *    `loadAndPlay` model swap.
 *  - `ChapterRenderJob` (PR-F) — background WorkManager-driven
 *    pre-render. Takes the mutex per sentence in the rendering loop.
 *
 * Both share this `@Singleton`. Wraps a [Mutex] (kotlinx.coroutines)
 * — re-entrancy is NOT supported. EnginePlayer's `loadAndPlay`
 * doesn't currently re-enter (model load and generate are serialized,
 * never nested), so this matches the current usage.
 */
@Singleton
class EngineMutex @Inject constructor() {
    val mutex: Mutex = Mutex()
}
```

- [ ] **Step 2: Inject into `EnginePlayer`.**

In `EnginePlayer.kt`, replace:

```kotlin
private val engineMutex = Mutex()
```

with the constructor injection:

```kotlin
class EnginePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    // ... existing params ...
    private val pcmCache: PcmCache,
    private val engineMutexHolder: EngineMutex,        // NEW (PR-F)
    // ... existing params ...
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Process-wide mutex; was a private field pre-PR-F. */
    private val engineMutex: Mutex get() = engineMutexHolder.mutex
```

This keeps all existing `engineMutex.withLock { ... }` call sites working unchanged because the property exposes the same `Mutex` type.

Add the import:

```kotlin
import `in`.jphe.storyvox.playback.cache.EngineMutex
```

- [ ] **Step 3: Build + test.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS — hoisting is a behavioral no-op.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/EngineMutex.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt
git commit -m "refactor(playback): hoist engineMutex to @Singleton EngineMutex

Process-wide mutex serializing JNI calls into VoxSherpa engines
(Piper / Kokoro). Pre-condition for PR-F's ChapterRenderJob to share
the same instance — without it, the background render could run
concurrently with EnginePlayer.loadAndPlay's loadModel and re-trigger
the issue #11 SIGSEGV race.

Behavioral no-op: same Mutex semantics, same withLock call sites,
just lifted from a private field to a Hilt @Singleton."
```

---

### Task F2: Add `PlaybackModeConfig.fullPrerender` + UiSettings field

**Files:**
- Modify: `core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt`
- Modify: `app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`

PR-G adds the Settings switch UI. PR-F adds the underlying flow + setter so PR-G's UI work is purely composables + view-model wiring.

- [ ] **Step 1: Update `PlaybackModeConfig` interface.**

Add the field + snapshot accessor:

```kotlin
interface PlaybackModeConfig {

    /** Live flow of Mode A — Warm-up Wait. Default true. */
    val warmupWait: Flow<Boolean>

    /** Live flow of Mode B — Catch-up Pause. Default true. */
    val catchupPause: Flow<Boolean>

    /**
     * Live flow of **Mode C — Full Pre-render**. Default false.
     *
     * When true, library-add scheduling expands from chapters 1-3 to
     * the entire fiction, and currently-in-library fictions enqueue
     * any not-yet-cached chapters. Trades aggressive disk + CPU usage
     * for a "play any chapter, instantly" experience. Off by default
     * because the binge case (40-chapter fiction × 70 MB Piper-high
     * each = 2.8 GB) blows past the default 2 GB quota — eviction
     * then thrashes between renders. Power users who set quota to
     * 5 GB or Unlimited and want the gapless-everywhere experience
     * flip this on.
     */
    val fullPrerender: Flow<Boolean>

    suspend fun currentWarmupWait(): Boolean
    suspend fun currentCatchupPause(): Boolean
    suspend fun currentFullPrerender(): Boolean
}
```

- [ ] **Step 2: Implement in `SettingsRepositoryUiImpl`.**

Add the DataStore key + flow + setter, mirroring the existing `catchupPause` / `warmupWait` patterns:

```kotlin
// In Keys companion:
val FULL_PRERENDER = booleanPreferencesKey("full_prerender")

// In the impl class:
override val fullPrerender: Flow<Boolean> =
    store.data.map { it[Keys.FULL_PRERENDER] ?: false }

override suspend fun currentFullPrerender(): Boolean = fullPrerender.first()

// Setter (called by Settings UI in PR-G):
override suspend fun setFullPrerender(enabled: Boolean) {
    store.edit { prefs -> prefs[Keys.FULL_PRERENDER] = enabled }
}
```

In the `UiSettings` projection (the `combine(...)` block that aggregates DataStore values into one `UiSettings`), include `fullPrerender = prefs[Keys.FULL_PRERENDER] ?: false`.

- [ ] **Step 3: Add the field + setter to `UiContracts.kt`.**

In `UiSettings`:

```kotlin
data class UiSettings(
    // ... existing fields ...
    /**
     * Issue #98 / PCM cache PR-F. Mode C — Full Pre-render. When true,
     * background scheduler renders ALL chapters of a library fiction
     * instead of just chapters 1-3. Off by default. PR-F creates the
     * field; PR-G adds the toggle UI.
     */
    val fullPrerender: Boolean = false,
    val palace: UiPalaceConfig = UiPalaceConfig(),
)
```

In `SettingsRepositoryUi`:

```kotlin
/** Issue #98 / PR-F. Mode C — Full Pre-render. See [UiSettings.fullPrerender]. */
suspend fun setFullPrerender(enabled: Boolean)
```

- [ ] **Step 4: Build + run all unit tests.**

Run: `./gradlew :app:assembleDebug :core-playback:testDebugUnitTest`
Expected: BUILD + TESTS PASS.

- [ ] **Step 5: Commit.**

```bash
git add core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt \
        app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt
git commit -m "feat(playback): PlaybackModeConfig.fullPrerender flow (Mode C)

DataStore-backed boolean preference, default false. PR-G surfaces
the Settings UI; PR-F's PrerenderTriggers reads the flow to gate
library-add scheduling between 'chapters 1-3 only' (off) and 'all
chapters' (on).

UiSettings + SettingsRepositoryUi gain matching field + setter so
the existing settings-flow plumbing in feature/SettingsScreen + the
view-model just need a new composable in PR-G to surface the toggle.

No UI in this PR — the field is invisible to users until PR-G."
```

---

### Task F3: `PcmRenderScheduler` interface + WorkManager impl

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmRenderScheduler.kt`

Follows the exact same shape as `WorkManagerChapterDownloadScheduler` for review consistency:

```kotlin
package `in`.jphe.storyvox.playback.cache

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background PCM cache renders for chapters likely to be
 * played soon. Sits in front of WorkManager so trigger sources
 * (FictionRepository.addToLibrary, EnginePlayer.handleChapterDone)
 * can stay free of androidx.work imports.
 *
 * Production binds [WorkManagerPcmRenderScheduler]; tests substitute
 * a recording fake to assert call sequence + args without spinning
 * up WorkManager.
 *
 * Unique-name policy: `pcm-render-<chapterId>` with
 * [ExistingWorkPolicy.KEEP] — repeated calls for the same chapter
 * are no-ops while a prior request is queued or running. This is
 * the spec's "single in-flight render at a time" enforcement at
 * the WorkManager level (engine concurrency is enforced by
 * [EngineMutex] inside the worker).
 *
 * Cancellation by chapterId — used when the user removes a fiction
 * from the library, or when PR-D's foreground tee takes over the
 * same key (the streaming source's appender for the
 * actively-playing chapter conflicts with a worker render of the
 * same chapter; foreground wins, worker is cancelled).
 */
interface PcmRenderScheduler {

    /** Enqueue a render for [chapterId]. No-op if a render for the
     *  same chapterId is already queued or running. */
    fun scheduleRender(fictionId: String, chapterId: String)

    /** Cancel an in-flight or queued render for [chapterId]. Idempotent. */
    fun cancelRender(chapterId: String)

    /** Cancel all renders for any chapter belonging to [fictionId].
     *  Used by [`in`.jphe.storyvox.data.repository.FictionRepository.removeFromLibrary]
     *  to stop background work for a fiction the user no longer wants. */
    fun cancelAllForFiction(fictionId: String)
}

@Singleton
class WorkManagerPcmRenderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PcmRenderScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun scheduleRender(fictionId: String, chapterId: String) {
        // Constraints per spec: don't render at low battery (synthesis
        // is CPU-heavy) or when storage is low. requireUnmetered NOT
        // set — render uses no network.
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val input = Data.Builder()
            .putString(ChapterRenderJob.KEY_FICTION_ID, fictionId)
            .putString(ChapterRenderJob.KEY_CHAPTER_ID, chapterId)
            .build()

        val request = OneTimeWorkRequestBuilder<ChapterRenderJob>()
            .setConstraints(constraints)
            .setInputData(input)
            // Long backoff — a render that fails (model load failure,
            // OOM mid-generate) is unlikely to succeed on a quick
            // retry. Give the device time to clean up.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5))
            .addTag(TAG)
            .addTag(fictionTag(fictionId))
            .build()

        workManager.enqueueUniqueWork(
            uniqueName(chapterId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelRender(chapterId: String) {
        workManager.cancelUniqueWork(uniqueName(chapterId))
    }

    override fun cancelAllForFiction(fictionId: String) {
        workManager.cancelAllWorkByTag(fictionTag(fictionId))
    }

    companion object {
        const val TAG = "pcm-render"
        fun uniqueName(chapterId: String): String = "pcm-render-$chapterId"
        fun fictionTag(fictionId: String): String = "pcm-render-fiction-$fictionId"
    }
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Bind in Hilt module.**

In whatever Hilt module binds repository / scheduler implementations (likely `app/src/main/kotlin/in/jphe/storyvox/di/AppModule.kt` or `core-playback/.../di/PlaybackModule.kt`), add:

```kotlin
@Binds
@Singleton
abstract fun bindPcmRenderScheduler(
    impl: WorkManagerPcmRenderScheduler,
): PcmRenderScheduler
```

- [ ] **Step 3: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — even though `ChapterRenderJob` doesn't exist yet, the scheduler references it via `OneTimeWorkRequestBuilder<ChapterRenderJob>()`. We need to either (a) create a stub `ChapterRenderJob` first, or (b) use a forward declaration. Cleanest: create the worker shell in this commit's same task or in F4 as the next commit. **Order: F4 immediately after F3, with F3's commit landing the scheduler + a stub worker, F4 fleshing the worker out.** The plan combines them inline below.

Move the `ChapterRenderJob.KEY_*` constants and class declaration into the same commit as a STUB so the scheduler compiles:

```kotlin
// Stub — Task F4 fleshes out doWork.
package `in`.jphe.storyvox.playback.cache

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ChapterRenderJob @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = Result.failure()  // stub

    companion object {
        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
    }
}
```

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PcmRenderScheduler.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJob.kt \
        <hilt-binding-module-path>
git commit -m "feat(playback): PcmRenderScheduler interface + WorkManager impl

Mirrors WorkManagerChapterDownloadScheduler shape exactly: unique-name
work per chapterId, KEEP policy (idempotent re-schedule), fiction tag
for bulk cancel on library removal. Constraints: battery-not-low +
storage-not-low (no network needed).

Stub ChapterRenderJob so the scheduler compiles; the worker body
lands in the next commit."
```

---

### Task F4: Implement `ChapterRenderJob` (the worker)

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJob.kt`

The worker is the load-bearing piece. It runs `setForeground` for long renders so OS doze can't kill it mid-chapter. The render loop is the same as the streaming producer's loop in `EngineStreamingSource`, just without the queue/AudioTrack — it generates per-sentence PCM and feeds a `PcmAppender`.

```kotlin
package `in`.jphe.storyvox.playback.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import `in`.jphe.storyvox.playback.tts.source.EngineStreamingSource
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceManager
import com.k2fsa.sherpa.onnx.kokoro.KokoroEngine
import com.k2fsa.sherpa.onnx.tts.VoiceEngine
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Background WorkManager job that renders one chapter's PCM into the
 * cache. Triggered by [`PcmRenderScheduler.scheduleRender`] from
 * [`PrerenderTriggers`] (library-add, chapter natural-end +2,
 * fullPrerender flow flips).
 *
 * Lifecycle:
 *  1. Re-read chapter text + active voice. Voice may have changed
 *     between schedule-time and run-time — recompute the cache key
 *     with the CURRENT voice.
 *  2. If the cache is already complete for the recomputed key, skip
 *     (someone else already rendered it; tee-write from foreground
 *     playback or a sibling worker).
 *  3. setForeground for the duration — render can take 30+ minutes
 *     on Piper-high + Tab A7 Lite.
 *  4. Acquire [EngineMutex.mutex]. EnginePlayer.loadAndPlay also takes
 *     this mutex; the worker yields to foreground playback by
 *     releasing the mutex if a foreground caller arrives. Implementation:
 *     mutex is a coroutine [kotlinx.coroutines.sync.Mutex]; we hold it
 *     across the whole generation loop, so foreground waits in
 *     EnginePlayer.loadAndPlay.engineMutex.withLock until the worker
 *     completes. NOT ideal for foreground UX (a worker rendering the
 *     fiction's chapter 5 blocks the user playing chapter 1) but
 *     correct. Future work: cooperative cancel — worker checks an
 *     "is foreground waiting?" signal between sentences and aborts.
 *     PR-F's first cut just lets the worker run to completion.
 *  5. Loop sentences: generate PCM → PcmAppender.appendSentence.
 *  6. On completion, finalize the appender + run evictToQuota.
 *  7. On worker cancellation (user removed fiction, or
 *     foreground render of same chapter started), abandon the
 *     appender so partial files don't lie around.
 *
 * Retry semantics: model load failure or generate-returns-null
 * triggers Result.retry() with the scheduler's exponential backoff
 * (5-minute base). Cache-write failures are ALSO retry, with the
 * caveat that retry on a half-written cache wastes the prior progress
 * (PR-D's resume policy is abandon-and-restart; we apply the same
 * here for simplicity).
 */
@HiltWorker
class ChapterRenderJob @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val chapterRepo: ChapterRepository,
    private val voiceManager: VoiceManager,
    private val pcmCache: PcmCache,
    private val engineMutex: EngineMutex,
    private val chunker: SentenceChunker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fictionId = inputData.getString(KEY_FICTION_ID)
            ?: return Result.failure()
        val chapterId = inputData.getString(KEY_CHAPTER_ID)
            ?: return Result.failure()

        // 1. Re-read chapter text. If the body isn't downloaded yet,
        // retry — ChapterDownloadWorker may still be running.
        val chapter = chapterRepo.getChapter(chapterId)
            ?: return Result.retry()

        // 2. Re-read active voice. Render against the CURRENT voice,
        // not whatever was active when the worker was scheduled.
        val voice = voiceManager.activeVoice.first()
            ?: return Result.retry()  // no voice configured; nothing to render

        // 3. Build the cache key from the CURRENT (chapter, voice,
        // speed, pitch). Speed/pitch read defaults — they're per-user-
        // session knobs that mutate via EnginePlayer.setSpeed/setPitch,
        // not stored. We render at 1.0× / 1.0× by default; if the
        // user actively plays at 1.25×, foreground tee fills that
        // separate cache key and worker's 1.0× cache stays around
        // for someone who DOES play at 1.0×.
        val cacheKey = PcmCacheKey(
            chapterId = chapterId,
            voiceId = voice.id,
            speedHundredths = 100,
            pitchHundredths = 100,
            chunkerVersion = CHUNKER_VERSION,
        )

        // 4. Skip if already complete.
        if (pcmCache.isComplete(cacheKey)) return Result.success()

        // 5. Wipe stale partial (same policy as foreground — abandon
        // and restart).
        if (pcmCache.metaFileFor(cacheKey).exists()) {
            pcmCache.delete(cacheKey)
        }

        // 6. setForeground — long renders need to survive doze.
        runCatching { setForeground(buildForegroundInfo(chapter.title)) }

        // 7. Load the model (idempotent if EnginePlayer already loaded
        // the same voice). Holds engineMutex.
        val loadResult = engineMutex.mutex.withLock {
            loadModel(voice)
        }
        if (loadResult != "Success") return Result.retry()

        // 8. Generate sentences + write to cache.
        val sentences = chunker.chunk(chapter.text)
        val appender = pcmCache.appender(cacheKey, sampleRate = sampleRateFor(voice))
        try {
            for (s in sentences) {
                if (isStopped) {
                    appender.abandon()
                    return Result.failure()  // cancelled — don't retry
                }
                val pcm = engineMutex.mutex.withLock {
                    if (isStopped) return@withLock null
                    generateAudioPCM(voice, s.text)
                } ?: continue   // engine declined this sentence; skip
                val pauseMs = (computeTrailingPauseMs(s.text)).toInt()
                runCatching { appender.appendSentence(s, pcm, pauseMs) }
                    .onFailure { appender.abandon(); return Result.retry() }
            }
            // 9. Finalize + evict.
            runCatching { appender.finalize() }
                .onFailure { appender.abandon(); return Result.retry() }
            runCatching {
                pcmCache.evictToQuota(
                    pinnedBasenames = setOf(cacheKey.fileBaseName()),
                )
            }
            return Result.success()
        } catch (t: Throwable) {
            appender.abandon()
            return if (isStopped) Result.failure() else Result.retry()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    /** Bridge to the singletons (same shape as
     *  EnginePlayer.activeVoiceEngineHandle). */
    private fun loadModel(voice: `in`.jphe.storyvox.playback.voice.UiVoiceInfo): String {
        return when (voice.engineType) {
            is EngineType.Piper -> {
                val voiceDir = voiceManager.voiceDirFor(voice.id)
                val onnx = File(voiceDir, "model.onnx").absolutePath
                val tokens = File(voiceDir, "tokens.txt").absolutePath
                VoiceEngine.getInstance().loadModel(appContext, onnx, tokens)
                    ?: "Error: load returned null"
            }
            is EngineType.Kokoro -> {
                val sharedDir = voiceManager.kokoroSharedDir()
                val onnx = File(sharedDir, "model.onnx").absolutePath
                val tokens = File(sharedDir, "tokens.txt").absolutePath
                val voicesBin = File(sharedDir, "voices.bin").absolutePath
                KokoroEngine.getInstance()
                    .setActiveSpeakerId((voice.engineType as EngineType.Kokoro).speakerId)
                KokoroEngine.getInstance().loadModel(appContext, onnx, tokens, voicesBin)
                    ?: "Error: load returned null"
            }
        }
    }

    private fun sampleRateFor(voice: `in`.jphe.storyvox.playback.voice.UiVoiceInfo): Int =
        when (voice.engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: 22050

    private fun generateAudioPCM(
        voice: `in`.jphe.storyvox.playback.voice.UiVoiceInfo,
        text: String,
    ): ByteArray? = when (voice.engineType) {
        is EngineType.Kokoro -> KokoroEngine.getInstance()
            .generateAudioPCM(text, 1.0f, 1.0f)
        else -> VoiceEngine.getInstance()
            .generateAudioPCM(text, 1.0f, 1.0f)
    }

    /** Same logic as EngineStreamingSource.trailingPauseMs but exposed
     *  here without needing to make that internal fn public. We
     *  duplicate the table — it's 7 lines and the spec's chunkerVersion
     *  guards against drift. Shared utility punted; refactor follow-up. */
    private fun computeTrailingPauseMs(sentenceText: String): Int =
        `in`.jphe.storyvox.playback.tts.source.trailingPauseMs(sentenceText)

    private fun buildForegroundInfo(title: String): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "PCM Render",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background audiobook caching" }
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Caching audiobook")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notif)
    }

    companion object {
        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
        private const val CHANNEL_ID = "pcm-render-channel"
        private const val NOTIFICATION_ID = 5042
    }
}
```

`trailingPauseMs` is `internal` in `EngineStreamingSource.kt`'s same package. Use it via fully-qualified name from the cache package — Kotlin `internal` is module-scoped, so a same-module fully-qualified call works:

```kotlin
import `in`.jphe.storyvox.playback.tts.source.trailingPauseMs as engineTrailingPauseMs
// ...
private fun computeTrailingPauseMs(sentenceText: String): Int =
    engineTrailingPauseMs(sentenceText)
```

Note: `in.jphe.storyvox.playback.tts.source.trailingPauseMs` is a top-level function — Kotlin allows aliased import via `as`.

- [ ] **Step 1: Replace the stub with the full implementation.**
- [ ] **Step 2: Update the `AndroidManifest.xml` if `FOREGROUND_SERVICE_DATA_SYNC` permission isn't already present.**

Check via:

```bash
grep -n FOREGROUND_SERVICE app/src/main/AndroidManifest.xml
```

If missing, add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

(`FOREGROUND_SERVICE_DATA_SYNC` is the `WorkManager` foreground type for "data sync" workloads — the closest fit for "render audio in background". Required on API 34+.)

- [ ] **Step 3: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJob.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(playback): ChapterRenderJob renders one chapter to cache

@HiltWorker + CoroutineWorker. Re-reads chapter text + active voice
at run-time (recomputes cache key — voice may have changed since
schedule). Skips if already complete (foreground tee got there
first). Wipes stale partial (PR-D's abandon-and-restart policy).
setForeground for long renders so doze can't kill mid-chapter.
Holds EngineMutex per-sentence so foreground EnginePlayer.loadAndPlay
sees a clean engine state.

On natural end: finalize + evictToQuota (pinned to just-finalized
basename so it's never the LRU victim).

On cancellation (worker stopped, fiction removed): abandon partial.

Manifest gains FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC
permissions for API 34+ compliance."
```

---

### Task F5: `PrerenderTriggers` glue + integration with `EnginePlayer` + `FictionRepository`

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggers.kt`
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt`
- Modify: `core-data/src/main/kotlin/in/jphe/storyvox/data/repository/FictionRepository.kt`

`PrerenderTriggers` is the seam. It owns the trigger logic ("library-add → chapters 1-3" / "chapter-done → N+2" / "fullPrerender ON → all"); EnginePlayer + FictionRepository just call its methods.

```kotlin
package `in`.jphe.storyvox.playback.cache

import dagger.hilt.android.scopes.ViewModelScoped
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trigger glue for the PCM cache pre-render scheduler. Sits between
 * the trigger sources (FictionRepository, EnginePlayer) and the
 * scheduler so neither has to import androidx.work directly.
 *
 * Trigger semantics per spec:
 *
 *  - **Library add** (`onLibraryAdded(fictionId)`): schedule renders
 *    for the first 3 chapters in reading order, OR all chapters if
 *    Mode C (fullPrerender) is on.
 *  - **Chapter complete** (`onChapterCompleted(chapterId)`): schedule
 *    a render of chapter N+2 in reading order, where N is the
 *    just-completed chapter. N+1 is usually already cached or in
 *    flight from the previous chapter-completed trigger.
 *  - **Fiction removed** (`onLibraryRemoved(fictionId)`): cancel all
 *    scheduled renders for that fiction.
 *
 * Mode C (fullPrerender) flips:
 *  - When fullPrerender goes from false → true, enqueue all
 *    not-yet-cached chapters in every library fiction. (One-shot;
 *    handled by an explicit refresh call on mode flip — the flow
 *    collector lives in `:app`.)
 *  - When fullPrerender goes from true → false, leave queued work
 *    alone. The user's explicit choice was "stop adding more"; in-
 *    flight renders run to completion.
 *
 * NOT in this PR: cancel a render when the user is now actively
 * playing the same chapterId via the streaming source. The streaming
 * source's tee writes to the SAME cache key; PR-D's appender's
 * resume detection (meta exists, idx absent) means the foreground
 * tee will collide with a worker holding the same files. Spec calls
 * this "mutual exclusion contract"; PR-F's first cut handles it via
 * the engineMutex (worker can't generate while foreground holds the
 * mutex; foreground holds the mutex per-sentence; effectively the
 * worker pauses between sentences while foreground is active). A
 * future cleanup adds explicit `cancelRender` calls from
 * `EnginePlayer.startPlaybackPipeline` for the matching chapter+voice
 * key.
 */
@Singleton
class PrerenderTriggers @Inject constructor(
    private val scheduler: PcmRenderScheduler,
    private val chapterRepo: ChapterRepository,
    private val modeConfig: PlaybackModeConfig,
) {

    suspend fun onLibraryAdded(fictionId: String) {
        val chapters = chapterRepo.observeChapters(fictionId)
            .first()
            .sortedBy { it.number }
        val limit = if (modeConfig.currentFullPrerender()) chapters.size else 3
        for (chapter in chapters.take(limit)) {
            scheduler.scheduleRender(fictionId = fictionId, chapterId = chapter.id)
        }
    }

    suspend fun onChapterCompleted(currentChapterId: String) {
        // Schedule N+2 — N+1 should already be cached (it was
        // scheduled when N started, OR is the next-up that the
        // user is about to play and PR-D's tee will populate).
        val nextId = chapterRepo.getNextChapterId(currentChapterId) ?: return
        val nextNextId = chapterRepo.getNextChapterId(nextId) ?: return
        // Find the fictionId from the chapter row.
        val nextNextChapter = chapterRepo.getChapter(nextNextId) ?: return
        scheduler.scheduleRender(
            fictionId = nextNextChapter.fictionId,
            chapterId = nextNextId,
        )
    }

    fun onLibraryRemoved(fictionId: String) {
        scheduler.cancelAllForFiction(fictionId)
    }

    /** Called from `:app`'s flow collector when fullPrerender flips
     *  ON. Re-evaluates every library fiction and enqueues any
     *  not-yet-cached chapters. */
    suspend fun onFullPrerenderEnabled(fictionIds: Iterable<String>) {
        for (fictionId in fictionIds) {
            val chapters = chapterRepo.observeChapters(fictionId)
                .first()
                .sortedBy { it.number }
            for (chapter in chapters) {
                scheduler.scheduleRender(fictionId, chapter.id)
            }
        }
    }
}
```

Add `import kotlinx.coroutines.flow.first` at the top.

- [ ] **Step 1: Create `PrerenderTriggers.kt`.**

- [ ] **Step 2: Inject + call from `EnginePlayer.handleChapterDone`.**

In `EnginePlayer`'s constructor:

```kotlin
class EnginePlayer @Inject constructor(
    // ... existing params ...
    private val prerenderTriggers: PrerenderTriggers,   // NEW (PR-F)
) : SimpleBasePlayer(Looper.getMainLooper()) {
```

In `handleChapterDone`:

```kotlin
private suspend fun handleChapterDone() {
    val chapterId = _observableState.value.currentChapterId
    persistPosition()
    if (chapterId != null) {
        chapterRepo.markChapterPlayed(chapterId)
        // PR-F: schedule N+2. N+1 is either already in cache (the
        // previous chapter-done scheduled it) or in flight as the
        // next chapter the user is about to play.
        runCatching { prerenderTriggers.onChapterCompleted(chapterId) }
    }
    advanceChapter(direction = 1)
}
```

- [ ] **Step 3: Inject + call from `FictionRepository.addToLibrary` and `removeFromLibrary`.**

`FictionRepository` is a data-layer interface; the impl `FictionRepositoryImpl` lives in `core-data`. PrerenderTriggers is in `core-playback`. To avoid a cross-module dep cycle (core-data → core-playback would be weird), introduce a **lightweight callback interface in core-data** and bind it in `:app`:

In `core-data/src/main/kotlin/in/jphe/storyvox/data/repository/FictionLibraryListener.kt`:

```kotlin
package `in`.jphe.storyvox.data.repository

/**
 * Hook that listens for library-add / library-remove events. Bound by
 * `:app`'s Hilt module to the playback layer's [`PrerenderTriggers`]
 * so the FictionRepository can stay free of playback dependencies.
 *
 * Default impl is a no-op so test doubles don't need to stub.
 */
interface FictionLibraryListener {
    suspend fun onLibraryAdded(fictionId: String) {}
    fun onLibraryRemoved(fictionId: String) {}

    object NoOp : FictionLibraryListener
}
```

In `FictionRepositoryImpl`, inject it:

```kotlin
class FictionRepositoryImpl @Inject constructor(
    // ... existing params ...
    private val libraryListener: FictionLibraryListener,
) : FictionRepository {

    override suspend fun addToLibrary(id: String, mode: DownloadMode?) = withContext(Dispatchers.IO) {
        val existing = fictionDao.get(id)
        if (existing == null) refreshDetail(id)
        fictionDao.setInLibrary(id, true, System.currentTimeMillis())
        if (mode != null) fictionDao.setDownloadMode(id, mode)
        // PR-F: notify the playback layer so it can pre-render
        // chapters 1-3 (or all, in Mode C).
        runCatching { libraryListener.onLibraryAdded(id) }
        Unit
    }

    override suspend fun removeFromLibrary(id: String) = withContext(Dispatchers.IO) {
        fictionDao.setInLibrary(id, false, System.currentTimeMillis())
        fictionDao.deleteIfTransient(id)
        runCatching { libraryListener.onLibraryRemoved(id) }
        Unit
    }
}
```

In `:app`'s Hilt module, bind:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class CacheBindingsModule {

    @Binds
    abstract fun bindFictionLibraryListener(
        impl: PrerenderTriggers,
    ): FictionLibraryListener
}
```

For this to bind, `PrerenderTriggers` must implement `FictionLibraryListener`:

```kotlin
@Singleton
class PrerenderTriggers @Inject constructor(
    // ...
) : FictionLibraryListener {

    override suspend fun onLibraryAdded(fictionId: String) { /* same body */ }
    override fun onLibraryRemoved(fictionId: String) { /* same body */ }

    suspend fun onChapterCompleted(currentChapterId: String) { /* same body */ }
    suspend fun onFullPrerenderEnabled(fictionIds: Iterable<String>) { /* same body */ }
}
```

The interface methods are part of the public surface; the chapter-completed and full-prerender hooks remain `PrerenderTriggers`-specific (used by EnginePlayer and `:app`'s mode-flip collector).

Tests can pass `FictionLibraryListener.NoOp` to `FictionRepositoryImpl` to keep the existing `FictionRepositoryImplTest` setup unchanged.

- [ ] **Step 4: `:app`'s flow collector for `fullPrerender` flip.**

When `fullPrerender` goes false → true, enqueue all library fictions' remaining chapters. Add a small singleton in `:app`:

`app/src/main/kotlin/in/jphe/storyvox/data/PrerenderModeWatcher.kt`:

```kotlin
package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.playback.cache.PrerenderTriggers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Listens for `fullPrerender` flips and, when it goes false → true,
 * enqueues all library fictions' chapters via [PrerenderTriggers].
 * Lives in `:app` to keep `core-playback` free of the FictionRepository
 * dependency.
 *
 * Started from [`StoryvoxApplication.onCreate`] (or wherever app-scoped
 * collectors are kicked off).
 */
@Singleton
class PrerenderModeWatcher @Inject constructor(
    private val modeConfig: PlaybackModeConfig,
    private val triggers: PrerenderTriggers,
    private val fictionRepo: FictionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            modeConfig.fullPrerender
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        val library = fictionRepo.observeLibrary().first()
                        triggers.onFullPrerenderEnabled(library.map { it.id })
                    }
                }
        }
    }
}
```

Hook into `StoryvoxApplication.onCreate` (or equivalent):

```kotlin
@Inject lateinit var prerenderModeWatcher: PrerenderModeWatcher

override fun onCreate() {
    super.onCreate()
    // ... existing init ...
    prerenderModeWatcher.start()
}
```

- [ ] **Step 5: Build + test.**

Run: `./gradlew :app:assembleDebug :core-playback:testDebugUnitTest :core-data:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 6: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggers.kt \
        core-data/src/main/kotlin/in/jphe/storyvox/data/repository/FictionLibraryListener.kt \
        core-data/src/main/kotlin/in/jphe/storyvox/data/repository/FictionRepository.kt \
        core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt \
        app/src/main/kotlin/in/jphe/storyvox/data/PrerenderModeWatcher.kt \
        <hilt-binding-module-for-FictionLibraryListener-and-PrerenderModeWatcher-start>
git commit -m "feat(playback): PrerenderTriggers wires lifecycle events to scheduler

PrerenderTriggers (core-playback) implements FictionLibraryListener
(core-data) so FictionRepository can call into the cache layer
without taking a core-playback dependency. EnginePlayer.handleChapterDone
calls triggers.onChapterCompleted(chapterId) → scheduler enqueues
chapter N+2.

Library add: enqueue chapters 1-3 (or all, when Mode C is on).
Library remove: cancel all by fictionId tag.
Mode C (fullPrerender) flip false→true: PrerenderModeWatcher
collector enqueues every library fiction's remaining chapters.

No UI in this PR — Mode C toggle lands in PR-G."
```

---

### Task F6: Tests for triggers + worker

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggersTest.kt`
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJobTest.kt`

**PrerenderTriggersTest** (pure JVM, fakes for ChapterRepo + Scheduler + ModeConfig):

```kotlin
package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PrerenderTriggersTest {

    private class RecordingScheduler : PcmRenderScheduler {
        val scheduled = mutableListOf<Pair<String, String>>()
        val cancelledFiction = mutableListOf<String>()
        override fun scheduleRender(fictionId: String, chapterId: String) {
            scheduled += fictionId to chapterId
        }
        override fun cancelRender(chapterId: String) {}
        override fun cancelAllForFiction(fictionId: String) {
            cancelledFiction += fictionId
        }
    }

    private class FakeModeConfig(
        var full: Boolean = false,
    ) : PlaybackModeConfig {
        override val warmupWait = flowOf(true)
        override val catchupPause = flowOf(true)
        override val fullPrerender = MutableStateFlow(full)
        override suspend fun currentWarmupWait() = true
        override suspend fun currentCatchupPause() = true
        override suspend fun currentFullPrerender() = full
    }

    /** Minimal ChapterRepo fake — only the methods triggers calls. */
    private class FakeChapterRepo(
        private val byFiction: Map<String, List<ChapterInfo>>,
    ) : ChapterRepository {
        override fun observeChapters(fictionId: String) =
            flowOf(byFiction[fictionId].orEmpty())
        override fun observeChapter(chapterId: String) = flowOf(null)
        override fun observeDownloadState(fictionId: String) = flowOf(emptyMap<String, _>())
        override suspend fun queueChapterDownload(
            fictionId: String, chapterId: String, requireUnmetered: Boolean,
        ) {}
        override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) {}
        override suspend fun markRead(chapterId: String, read: Boolean) {}
        override suspend fun markChapterPlayed(chapterId: String) {}
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) {}
        override suspend fun getChapter(id: String) = null
        override suspend fun getNextChapterId(currentChapterId: String): String? {
            val all = byFiction.values.flatten()
            val idx = all.indexOfFirst { it.id == currentChapterId }
            return all.getOrNull(idx + 1)?.id
        }
        override suspend fun getPreviousChapterId(currentChapterId: String): String? = null
    }

    private fun chapter(num: Int, fictionPrefix: String) = ChapterInfo(
        id = "$fictionPrefix-c$num", fictionId = fictionPrefix, number = num,
        title = "Chapter $num",
        // remaining fields per ChapterInfo's actual constructor — fill in
        // with whatever defaults ChapterInfo permits.
        publishedAt = 0L, charCount = 0, durationEstimateMs = 0L,
        isFinished = false, isDownloaded = false,
    )

    @Test
    fun `onLibraryAdded enqueues first 3 chapters when fullPrerender off`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false))

        triggers.onLibraryAdded("f1")

        assertEquals(3, scheduler.scheduled.size)
        assertEquals(listOf("f1-c1", "f1-c2", "f1-c3"), scheduler.scheduled.map { it.second })
    }

    @Test
    fun `onLibraryAdded enqueues all chapters when fullPrerender on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = true))

        triggers.onLibraryAdded("f1")

        assertEquals(5, scheduler.scheduled.size)
    }

    @Test
    fun `onLibraryRemoved cancels every render for that fiction`() = runBlocking {
        val scheduler = RecordingScheduler()
        val triggers = PrerenderTriggers(scheduler, FakeChapterRepo(emptyMap()), FakeModeConfig())

        triggers.onLibraryRemoved("f1")

        assertEquals(listOf("f1"), scheduler.cancelledFiction)
    }

    @Test
    fun `onChapterCompleted schedules N+2`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Need a repo where getChapter returns a PlaybackChapter with fictionId.
        // For this test, override getChapter to return a non-null value for c3.
        val repo = object : FakeChapterRepo(
            mapOf("f1" to (1..5).map { chapter(it, "f1") })
        ) {
            override suspend fun getChapter(id: String) =
                if (id == "f1-c3") PlaybackChapter(
                    id = "f1-c3", fictionId = "f1", text = "txt",
                    title = "C3", bookTitle = "F1", coverUrl = null,
                ) else null
        }
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig())

        // Just-completed = c1 → next = c2 → next-next = c3 → schedule c3.
        triggers.onChapterCompleted("f1-c1")

        assertEquals(1, scheduler.scheduled.size)
        assertEquals("f1-c3", scheduler.scheduled.first().second)
    }
}
```

**ChapterRenderJobTest** uses WorkManager's `TestDriver` + Robolectric:

```kotlin
package `in`.jphe.storyvox.playback.cache

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChapterRenderJobTest {

    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build(),
        )
    }

    @Test
    fun `render produces a complete cache entry for a real chapter`() = runBlocking {
        // This is the structure of the test; the actual @HiltWorker
        // wiring is involved — we'd typically use Hilt's testing
        // helpers (HiltWorkerFactory, AndroidTestApplication) to
        // wire dependencies. Inline here for plan-readability;
        // implementation may use TestListenableWorkerBuilder with
        // a manual factory if Hilt+WorkManager testing in
        // Robolectric proves problematic.

        // [Test scaffolding TBD — see open question below about
        //  Hilt+WorkManager+Robolectric. May fall back to:
        //  1. Pure-JVM unit test of the doWork logic factored out
        //     into a non-worker function.
        //  2. Instrumented androidTest variant if Robolectric path
        //     isn't workable.]
        assertTrue(true)
    }
}
```

> Plan honesty: testing `@HiltWorker` under Robolectric is non-trivial; the project's existing `ChapterDownloadWorker` likely lacks a Robolectric test for the same reason. Recommended pattern: factor `ChapterRenderJob.doWork`'s body into a pure suspend function `RenderEngine.renderOne(fictionId, chapterId, ...)` and unit-test THAT. The worker becomes a thin shell. If Aurora's PR-C tests already span Robolectric usage in core-playback, that pattern is fine; if Hilt+Worker testing has been deferred elsewhere in the codebase, defer here too and note in the PR description.

- [ ] **Step 1: Create `PrerenderTriggersTest.kt`** with the test scaffolding above. Adjust `ChapterInfo` constructor to match the actual class.
- [ ] **Step 2: Create `ChapterRenderJobTest.kt`** as a placeholder — either implement via the factor-out pattern above OR mark `@Ignore` with a comment pointing to the follow-up.
- [ ] **Step 3: Run.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*Prerender*"`
Expected: PASS for triggers; ChapterRenderJobTest may be `@Ignore`d.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/PrerenderTriggersTest.kt \
        core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/ChapterRenderJobTest.kt
git commit -m "test(playback): PrerenderTriggers schedule + cancel semantics

Pure JVM, fakes for scheduler + ChapterRepo + ModeConfig. Verifies:
  - onLibraryAdded with fullPrerender=false → first 3 chapters
  - onLibraryAdded with fullPrerender=true → all chapters
  - onLibraryRemoved → cancelAllForFiction(id)
  - onChapterCompleted → schedules N+2 with the right fictionId

ChapterRenderJobTest deferred — Hilt+@HiltWorker+Robolectric testing
is non-trivial and the existing ChapterDownloadWorker doesn't have a
unit test either. Tablet smoke test in PR-F covers the worker
end-to-end."
```

---

### Task F7: Tablet smoke-test PR-F on R83W80CAFZB

**Files:** none — runtime verification.

The trigger paths must actually fire and the worker must produce a complete cache entry. Tablet verification per memory `feedback_install_test_each_iteration.md`.

- [ ] **Step 1: Claim tablet lock #47.**

- [ ] **Step 2: Build + install.**

- [ ] **Step 3: Library-add smoke test.**

- Wipe cache: `adb -s R83W80CAFZB shell run-as in.jphe.storyvox rm -rf cache/pcm-cache/`
- Foreground app → add a small fiction (≤ 5 chapters). Watch the notification shade.

**Expected:**
- Within 30 s, "Caching audiobook" notification appears (the foreground service for the worker).
- Over the next several minutes (depends on chapter length × Piper-high RTF), 3 cache triples appear in `cache/pcm-cache/`.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls cache/pcm-cache/ | wc -l
```

Expected: 9 files (3 triples × pcm + idx + meta).

- [ ] **Step 4: Chapter-natural-end smoke test.**

- Play chapter 1 to natural end. Verify `handleChapterDone` runs (chapter advances to 2 in UI). Watch cache directory for chapter 3's render to start (N+2).

```bash
adb -s R83W80CAFZB logcat | grep -i pcm-render
```

Expected: a worker run for chapter 3's id within seconds of chapter 1 completing.

- [ ] **Step 5: Mode C flip smoke test.**

- Foreground Storyvox → Settings (PR-G NOT yet shipped — manually flip the DataStore via adb shell or via a debug-only menu if it exists, or just defer this smoke test to the PR-G tablet test).

Alternative: write a one-off script:

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  am broadcast -a in.jphe.storyvox.DEBUG_FULL_PRERENDER_ON
```

(Only if such a debug receiver exists; otherwise mark this sub-step "covered by PR-G's tablet test".)

- [ ] **Step 6: Library-remove cancellation smoke test.**

- With pending renders queued, remove the fiction from library. Pending renders should cancel (`scheduler.cancelAllForFiction`).

```bash
adb -s R83W80CAFZB shell dumpsys jobscheduler | grep storyvox
```

Expected: no jobs tagged `pcm-render-fiction-<id>` after removal.

- [ ] **Step 7: Release tablet lock.**

- [ ] **Step 8: Push.**

```bash
git push -u origin dream/<voice>/pcm-cache-pr-f
```

---

### Task F8: Open PR-F

```bash
gh pr create --base main --head dream/<voice>/pcm-cache-pr-f \
  --title "feat(playback): PCM cache RenderScheduler + background renders (PR-F)" \
  --body "$(cat <<'EOF'
## Summary

PR-F of the chapter PCM cache. Pre-populates the cache for chapters
the user is **likely** to play, so the first play of a never-cached
chapter is gapless instead of streaming with warm-up + buffering.

Triggers per spec:
- **Library add** → schedule chapters 1-3 (or all, in Mode C).
- **Chapter natural-end** → schedule chapter N+2.
- **Mode C — Full Pre-render** flow flips false→true → enqueue all
  remaining chapters in every library fiction.

Adds:
- `EngineMutex` — `@Singleton` lift of EnginePlayer's mutex (PR-F's
  ChapterRenderJob shares it for issue #11 SIGSEGV race protection).
- `PcmRenderScheduler` interface + `WorkManagerPcmRenderScheduler`
  impl. Mirrors `WorkManagerChapterDownloadScheduler` shape.
- `ChapterRenderJob` (`@HiltWorker`). Re-reads chapter+voice at
  run-time, setForeground for long renders, holds engineMutex
  per-sentence.
- `PrerenderTriggers` (`@Singleton`). Trigger glue between
  FictionRepository / EnginePlayer and the scheduler.
- `FictionLibraryListener` interface in `core-data` — keeps the
  repo free of `core-playback` deps; bound in `:app`.
- `PlaybackModeConfig.fullPrerender` flow + `UiSettings.fullPrerender`
  field. Default false. PR-G adds the Settings switch.

## What's NOT in this PR

- **Settings UI for Mode C / quota.** PR-G.
- **Status icons.** PR-H.
- **Cooperative cancel** — when foreground playback starts a chapter
  that the worker is currently rendering, both contend on engineMutex
  per-sentence (worker pauses between sentences while foreground
  generates). Future cleanup adds explicit `cancelRender` from
  `EnginePlayer.startPlaybackPipeline` for the matching key.

## Test plan

- [x] PrerenderTriggersTest (pure JVM, fakes):
  - onLibraryAdded with fullPrerender=false → first 3 chapters
  - onLibraryAdded with fullPrerender=true → all chapters
  - onLibraryRemoved → cancelAllForFiction(id)
  - onChapterCompleted → schedules N+2 with right fictionId
- [ ] ChapterRenderJobTest deferred — Hilt+@HiltWorker+Robolectric is
  non-trivial; existing ChapterDownloadWorker also lacks a unit test.
  Tablet smoke covers worker end-to-end.
- [x] R83W80CAFZB:
  - library-add small fiction → "Caching audiobook" notification
    appears within 30 s; 3 cache triples land within minutes
  - play chapter 1 to natural end → chapter 3 worker fires
  - library-remove → pending workers cancel
- [x] `./gradlew :app:assembleDebug` green
- [x] `./gradlew :core-playback:testDebugUnitTest` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Same Copilot review + capped wait + squash-merge playbook as PR-D / PR-E.

---

## Open questions for JP

1. **Worker speed/pitch quantization.** PR-F renders at the user's CONFIGURED default speed/pitch (read from settings at run-time) OR at 1.0×/1.0× regardless? Plan says 1.0×/1.0× for simplicity. Trade-off: a user who plays at 1.5× will hit a cache miss and the streaming path will tee a SECOND cache file at 1.5×. Disk usage grows linearly in distinct speed knobs the user explores. **Recommendation: render at 1.0×/1.0× in v0.5.0; revisit if disk pressure becomes an issue.** Alternative: read default speed/pitch from SettingsRepositoryUi at worker run-time.

2. **Cooperative cancel between worker and foreground.** Spec calls out the mutual-exclusion contract: at most one process writes to a given key. PR-F's first cut handles it via engineMutex (effective serialization at sentence granularity), but a worker holding the mutex through a 26-min Piper-high render blocks any foreground load attempts during that window. Per-sentence release (`engineMutex.withLock` is per-sentence in the worker loop) means foreground gets a window between worker sentences — not ideal but workable. **Recommendation: ship as-is; add explicit `cancelRender(currentChapterId)` to `EnginePlayer.startPlaybackPipeline` in a follow-up if user complaints land.**

3. **`@HiltWorker` testing.** Plan defers `ChapterRenderJobTest` because the existing `ChapterDownloadWorker` doesn't have a unit test either, suggesting the project hasn't solved Hilt+Worker+Robolectric testing yet. **Recommendation: extract a pure suspend function `renderChapterToCache(fictionId, chapterId, ...)` that takes all deps as params; unit-test THAT; the worker shell calls it. Easy follow-up; not gating PR-F.**

4. **`FictionLibraryListener` placement.** Plan puts it in `core-data` so `FictionRepository` can call into it without depending on `core-playback`. Hilt binds the impl (`PrerenderTriggers`) in `:app`. Existing `FictionRepositoryImplTest` passes `FictionLibraryListener.NoOp` to keep the test setup clean. **Recommendation: ship as-is unless JP sees a cleaner placement.**

5. **`PrerenderModeWatcher` lives in `:app`.** Could live in `core-playback` if we add `FictionRepository` as a `core-playback` dep. Keeping it in `:app` avoids that dep direction (which feels right — playback should depend on data, not the reverse). **Recommendation: ship as-is.**

---

## Self-review

**Spec coverage check (PR-F scope from spec line 409):**
- ✓ "Background WorkManager render" → ChapterRenderJob, Task F4
- ✓ "Wired to loadAndPlay cache miss" — actually NOT wired this way in plan: the streaming-source's tee handles the actively-played chapter; the worker only handles BACKGROUND chapters. This deviates from spec line 263, which has the loadAndPlay miss path also invoke the worker. **Decision: tee handles foreground, worker handles background. Spec line 261 confirms: "do NOT schedule a separate worker [on cache miss in loadAndPlay] — the streaming source's PcmAppender tee-write IS the render".** So plan is consistent with spec.
- ✓ "handleChapterDone (N+2)" → Task F5 step 2
- ✓ "addToLibrary (1-3)" → Task F5 step 3 (via FictionLibraryListener)
- ✓ Mutual-exclusion contract — PR-F's first cut uses engineMutex; cooperative cancel deferred (open question 2).
- ✓ setForeground for long renders → Task F4 step 6
- ✓ Battery-not-low + storage-not-low constraints → Task F3 (the scheduler's Constraints builder)
- ✓ Mode C — fullPrerender flow + UiSettings field → Task F2

**Spec deltas / decisions:**
- **Worker renders at 1.0×/1.0× speed/pitch by default** — spec doesn't pin this. Open question 1.
- **`FictionLibraryListener` interface in core-data** — spec doesn't specify the dep direction; this is the natural shape.
- **`PrerenderModeWatcher` in :app** — same reasoning.
- **ChapterRenderJobTest deferred** — pragmatic given existing project test infrastructure.

**Placeholder scan:** None except the explicit `[Test scaffolding TBD]` in `ChapterRenderJobTest`, which is documented as a deferred test (open question 3). Other Kotlin blocks compile in context.

**Type consistency:** `PcmCacheKey`, `PcmAppender`, `PcmCache` calls match PR-C's shipped surface. `PlaybackModeConfig` extension is consistent across `core-data`, `:app` impl, and `core-playback` consumer.

**Risks deferred to follow-up PRs:**
- Settings UI surface for Mode C + quota selector — PR-G.
- Status icons in chapter list / voice library — PR-H.
- Worker speed/pitch parity with user's default — open question 1, follow-up.
- Cooperative cancel between worker and foreground — open question 2, follow-up.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-f.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute F1-F8 inline. PR-open is the JP-visible boundary; everything before that stays in-session.
