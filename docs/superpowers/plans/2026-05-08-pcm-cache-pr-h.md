# PCM Cache PR-H Implementation Plan — Status Icons + UX Polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Visible-to-user polish that closes out the cache work. After PR-A through PR-G, the cache works end-to-end but its state is invisible to the user — they hear cached vs streaming as a difference in warm-up + buffering, but no UI signal tells them "this chapter is ready to play instantly". PR-H adds three signals plus a per-fiction destructive action:

1. **Per-chapter cache state badge** in the chapter list. Three states:
   - **None** — no cache files for this (chapter, current voice). Plain card, no badge.
   - **Partial** — meta exists, idx absent (a render is in progress: PR-D's tee from active playback OR PR-F's worker). Pulsing sparkline-style badge.
   - **Complete** — idx present, cache hit on play. Solid "instant play" badge (lightning bolt or filled disc icon).
2. **Voice library cached-MB indicator** per voice row. Each voice gets a small "X MB cached" subtitle showing how much disk this voice's renders occupy. Lets users see which voices they've actually used.
3. **"Clear this fiction's cache"** action on the Fiction Detail screen overflow menu. Wipes all cache entries whose `meta.json` references this fiction's chapter IDs. Useful when storage is a concern for a specific fiction the user is done with.

Plus low-stakes copy refinements throughout — PR-G's Settings subtitles, PR-F's notification text — to round off the v0.5.0 user-facing story.

**Architecture:**

```
Per-chapter badge
     │
     ▼
ChapterCardState gains `cacheState: ChapterCacheState`
     │
     ▼
FictionDetailViewModel observes cache state per chapter
     │
     ├──► For each chapter in the fiction:
     │    PcmCache.isComplete(currentVoiceKey)        → Complete
     │    OR PcmCache.metaFileFor(...).exists()       → Partial
     │    OR neither                                  → None
     │
     ▼
ChapterCard renders the badge with state-specific Icon
```

```
Voice library
     │
     ▼
VoiceLibraryViewModel computes cachedBytesByVoice from PcmCache
     │
     ▼
UiVoiceInfo gains `cachedBytes: Long` (sum of pcm files whose meta.voiceId matches)
     │
     ▼
VoiceRow renders "X MB cached" subtitle
```

```
"Clear fiction cache"
     │
     ▼
FictionDetailViewModel.clearFictionCache()
     │
     ▼
For each chapterId in the fiction:
    PcmCache.deleteAllForChapter(chapterId)
```

**Tech stack:** Compose Material 3, kotlinx.coroutines for the cache-state flow per chapter, Robolectric for the new repository tests. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — implementation outline line "PR H — Polish. Per-chapter cache status icon, 'Clear cache' affordance." Plus the spec's "out of scope" section that explicitly carves out per-chapter status icons "for v0.5.x; not required for THIS PR" — meaning required for PR-H, the polish lane.

**Out of scope (deferred to v0.5.x or follow-up):**

- **Animated per-chapter badge transitions.** PR-H ships a static state badge; cross-fade animations between None → Partial → Complete are nice-to-have, not load-bearing.
- **Voice library "render queue" indicator.** Showing "Cori has 3 chapters queued for render" requires WorkManager state introspection per voice; complexity > polish payoff.
- **"Pin this fiction" affordance** (always-keep-cached, never-evict). Spec mentions it as nice-to-have, deferred. Could land alongside the Mode C work but JP hasn't asked.
- **Per-voice "wipe all caches for this voice"** — symmetry with per-fiction, but voices are usually fewer than fictions and the use case is "I don't like this voice anymore" which the existing voice-uninstall flow can wipe later. Keep PR-H scoped.
- **Cache state for non-active voices.** The chapter badge only shows the state for the CURRENT voice. A "cori partial, amy complete" badge breakdown is N+1 work. Defer.

---

## File Structure

### New files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspector.kt`
  Read-only wrapper around PcmCache + PcmCacheManifest. Methods: `chapterStateFor(chapterId, voiceId)`, `bytesUsedByVoice(voiceId)`. PR-H's UI repos call it; tests use it directly.

### Modified files

- `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCard.kt`
  Add `cacheState: ChapterCacheState` to `ChapterCardState`. Render a badge in the existing card layout (between title and chapter number, or a small overlay icon).
- `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCacheBadge.kt`
  New small composable for the badge — Icon + tooltip. Three states: `None` (invisible / 0-alpha), `Partial` (pulsing animation), `Complete` (solid).
- `feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailViewModel.kt`
  Combine `CacheStateInspector.chapterStatesFor(fictionId, voiceId)` flow into the chapter list state. Add `clearFictionCache(fictionId)`.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailScreen.kt`
  Map `UiChapter.cacheState` into `ChapterCardState.cacheState`. Add overflow-menu entry "Clear fiction cache" with confirmation dialog.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`
  Add `cacheState: ChapterCacheState` to `UiChapter`. Add `cachedBytes: Long` to `UiVoiceInfo`. New enum `ChapterCacheState`.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryViewModel.kt`
  Map `CacheStateInspector.bytesUsedByVoice` into the per-voice `cachedBytes` field.
- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/UiVoiceInfo.kt`
  Add `cachedBytes: Long = 0L` field.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryScreen.kt`
  Render `cachedBytes` in the existing `VoiceRow` layout — one-line subtitle "X MB cached".

### New tests

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspectorTest.kt`
  Robolectric. Verifies state classification (None / Partial / Complete) and per-voice byte sums.
- `core-ui/src/test/kotlin/in/jphe/storyvox/ui/component/ChapterCacheBadgeTest.kt`
  Compose-level test (or visual regression if the project has snapshot testing) — verifies the three states render distinct icons / colors. **If snapshot testing isn't set up, defer this to manual visual review** and document in the PR.

---

## Conventions

- All commits use conventional-commit style. Branch is `dream/<voice>/pcm-cache-pr-h`.
- Run from worktree root.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest :feature:testDebugUnitTest`.
- Full app build: `./gradlew :app:assembleDebug`.
- Tablet smoke-test on R83W80CAFZB **required** — cache-state badges are visible-to-user; review must inspect them on real chapters. Per memory `feedback_install_test_each_iteration.md`.
- Selective `git add` per CLAUDE.md.
- **No version bump in this PR.** Orchestrator handles release bundling. PR-H closes out the cache series; the bundling commit may bump to v0.5.0.

---

## Sub-change sequencing

Six commits inside the PR:

1. `feat(playback): CacheStateInspector — read-only state queries` — backend.
2. `feat(ui): ChapterCacheBadge composable + ChapterCacheState enum` — UI primitive.
3. `feat(ui): ChapterCard surfaces cache state badge` — wire badge into existing card layout.
4. `feat(fiction): chapter list shows per-chapter cache state` — view-model + screen wire-up.
5. `feat(fiction): Clear fiction cache action in detail overflow menu` — destructive action.
6. `feat(voicelibrary): per-voice cached-MB indicator` — voice library wire-up.
7. `test(playback): CacheStateInspector unit tests` — Robolectric.

---

## PR-H Tasks

### Task H1: `CacheStateInspector` read-only queries

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspector.kt`

The inspector is a thin read-only layer over `PcmCache` + `PcmCacheManifest`. Two query patterns:

1. **Per-(chapter, voice) state** — used by chapter-list UI. Maps to `None / Partial / Complete`.
2. **Per-voice byte sum** — used by voice library UI. Lists meta.json files, filters by `meta.voiceId == voiceId`, sums the corresponding `.pcm` file lengths.

Both are I/O-light (a few `File.exists` syscalls or one directory listing + JSON parses); no caching needed. The Settings cache-stats poll (PR-G's CacheStatsRepository) does similar work and stays at 5 s — PR-H's per-fiction queries fire on screen-enter and don't need to re-poll.

```kotlin
package `in`.jphe.storyvox.playback.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only inspector over the PCM cache state. Used by the UI layer
 * (FictionDetailViewModel, VoiceLibraryViewModel) to surface "this
 * chapter is cached" / "this voice has X MB cached" indicators
 * without coupling the UI to PcmCache's writer surface.
 *
 * Cheap to call — every method is a few file syscalls or a single
 * directory listing + JSON parses. Fine to call per-render on a
 * Compose screen so long as the call is dispatched off the main
 * thread (`withContext(Dispatchers.IO)` is built in).
 *
 * Thread-safe: backed by [PcmCache]'s already-thread-safe read
 * surface (isComplete, totalSizeBytes, metaFileFor — all `File.exists`
 * or directory listing).
 */
@Singleton
class CacheStateInspector @Inject constructor(
    private val cache: PcmCache,
) {

    /**
     * Classify the cache state of [chapterId] for [voiceId] at the
     * "default" speed/pitch (1.0× / 1.0×) and current chunker version.
     * Picks the most-likely-to-be-relevant key — most users have
     * their default speed at 1.0×; non-default-speed caches surface
     * elsewhere if needed.
     *
     *  - [ChapterCacheState.Complete] — `.idx.json` exists for the key.
     *    Play this chapter and it's gapless.
     *  - [ChapterCacheState.Partial] — `.meta.json` exists, `.idx.json`
     *    doesn't. A render is in flight (PR-D's tee or PR-F's worker)
     *    OR a prior render was killed/abandoned and not yet cleaned up.
     *  - [ChapterCacheState.None] — no files for this key.
     */
    suspend fun chapterStateFor(
        chapterId: String,
        voiceId: String,
        chunkerVersion: Int,
    ): ChapterCacheState = withContext(Dispatchers.IO) {
        val key = PcmCacheKey(
            chapterId = chapterId,
            voiceId = voiceId,
            speedHundredths = 100,
            pitchHundredths = 100,
            chunkerVersion = chunkerVersion,
        )
        when {
            cache.isComplete(key) -> ChapterCacheState.Complete
            cache.metaFileFor(key).exists() -> ChapterCacheState.Partial
            else -> ChapterCacheState.None
        }
    }

    /**
     * Bulk classification for every chapter in [chapterIds] under
     * [voiceId] + [chunkerVersion]. Cheaper than N individual calls
     * because we list the cache directory once and decode meta files
     * lazily — useful for the chapter list which often has 100+ rows.
     *
     * Returns a map keyed by chapterId. Missing entries map to
     * [ChapterCacheState.None].
     */
    suspend fun chapterStatesFor(
        chapterIds: Collection<String>,
        voiceId: String,
        chunkerVersion: Int,
    ): Map<String, ChapterCacheState> = withContext(Dispatchers.IO) {
        if (chapterIds.isEmpty()) return@withContext emptyMap()
        // For each chapter, build the expected key + check the two
        // marker files. Fast path: just two File.exists per chapter.
        chapterIds.associateWith { chapterId ->
            val key = PcmCacheKey(
                chapterId = chapterId,
                voiceId = voiceId,
                speedHundredths = 100,
                pitchHundredths = 100,
                chunkerVersion = chunkerVersion,
            )
            when {
                cache.isComplete(key) -> ChapterCacheState.Complete
                cache.metaFileFor(key).exists() -> ChapterCacheState.Partial
                else -> ChapterCacheState.None
            }
        }
    }

    /**
     * Sum of `.pcm` file sizes whose `.meta.json` reports
     * `voiceId == voiceId`. O(n) over all cache entries; for a 5 GB
     * cache that's at most ~70 chapters × stat + JSON-parse, single-
     * digit ms on internal flash. Voice library polls infrequently
     * (screen-enter), so no need to memoize.
     */
    suspend fun bytesUsedByVoice(voiceId: String): Long = withContext(Dispatchers.IO) {
        val rootDir = cache.rootDirectory()
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?: return@withContext 0L
        var total = 0L
        for (mf in metaFiles) {
            val meta = runCatching {
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            if (meta.voiceId == voiceId) {
                val basename = mf.name.removeSuffix(META_SUFFIX)
                total += java.io.File(rootDir, "$basename.pcm").length()
            }
        }
        total
    }

    /**
     * Bulk per-voice byte sum. Useful for voice library which has
     * 50+ voices. Single directory walk, single JSON parse per meta.
     */
    suspend fun bytesUsedByEveryVoice(): Map<String, Long> = withContext(Dispatchers.IO) {
        val rootDir = cache.rootDirectory()
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?: return@withContext emptyMap()
        val byVoice = mutableMapOf<String, Long>()
        for (mf in metaFiles) {
            val meta = runCatching {
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            val basename = mf.name.removeSuffix(META_SUFFIX)
            val pcmLen = java.io.File(rootDir, "$basename.pcm").length()
            byVoice[meta.voiceId] = (byVoice[meta.voiceId] ?: 0L) + pcmLen
        }
        byVoice
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
    }
}

/** UI-facing classification of one chapter's cache state. */
enum class ChapterCacheState {
    /** No cache files for this (chapter, voice). Default state. */
    None,
    /** A render is in progress (meta written, idx absent), OR a prior
     *  render was killed and not yet cleaned up. Treated as a UI-
     *  visible "in flight" badge. */
    Partial,
    /** Index sidecar present — chapter plays gapless. */
    Complete,
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspector.kt
git commit -m "feat(playback): CacheStateInspector — read-only state queries

UI-facing inspector over PcmCache. Three query methods:
  - chapterStateFor(chapterId, voiceId, chunkerVersion) → None/Partial/Complete
  - chapterStatesFor(ids, voiceId, ...) → bulk map
  - bytesUsedByVoice(voiceId) → Long (sum of pcm sizes for matching meta)
  - bytesUsedByEveryVoice() → Map<String, Long>

Wraps File.exists + listFiles + meta.json parse. Cheap enough to
call per-screen-enter without memoization. Used by PR-H's chapter
list cache badges and voice library cached-MB indicators."
```

---

### Task H2: `ChapterCacheBadge` composable + `ChapterCacheState` import in core-ui

**Files:**
- Create: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCacheBadge.kt`

`ChapterCacheState` lives in `core-playback`. `core-ui` already depends on `core-playback` (it imports `UiVoiceInfo` from there per existing imports in `VoiceRow`). Re-export the enum or define a parallel UI enum.

Simpler: import the existing one. Add to `ChapterCardState` in `ChapterCard.kt`:

```kotlin
import `in`.jphe.storyvox.playback.cache.ChapterCacheState

@Immutable
data class ChapterCardState(
    val number: Int,
    val title: String,
    val publishedRelative: String,
    val durationLabel: String,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
    val isCurrent: Boolean,
    /** PCM cache state for this chapter under the user's currently-active
     *  voice. Defaults to None for back-compat with code paths that
     *  haven't yet computed cache state (e.g. previews, tests). */
    val cacheState: ChapterCacheState = ChapterCacheState.None,
)
```

The badge composable:

```kotlin
package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.playback.cache.ChapterCacheState

/**
 * Small badge inside a [ChapterCard] showing the chapter's PCM cache
 * state for the active voice.
 *
 *  - [ChapterCacheState.None]: invisible (no Box rendered).
 *  - [ChapterCacheState.Partial]: pulsing hourglass icon, primary color.
 *    Communicates "render in progress; will be ready soon".
 *  - [ChapterCacheState.Complete]: solid lightning bolt, primary color.
 *    Communicates "play and it starts instantly, no warm-up".
 *
 * Tooltip via [contentDescription] for accessibility. The icons are
 * Material Icons primitives — no asset adds.
 */
@Composable
fun ChapterCacheBadge(
    state: ChapterCacheState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ChapterCacheState.None -> Unit  // invisible
        ChapterCacheState.Partial -> {
            // Pulse alpha 0.4 ↔ 1.0 over 1.4 s so it visibly differs
            // from a static "downloaded" or "finished" icon.
            val transition = rememberInfiniteTransition(label = "pcm-cache-pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pcm-cache-pulse-alpha",
            )
            Icon(
                imageVector = Icons.Outlined.HourglassTop,
                contentDescription = "Caching in progress",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .size(20.dp)
                    .alpha(alpha)
                    .semantics { contentDescription = "Caching in progress" },
            )
        }
        ChapterCacheState.Complete -> {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = "Cached for instant play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .size(20.dp)
                    .semantics { contentDescription = "Cached for instant play" },
            )
        }
    }
}
```

Note: `transition.animateFloat` is the imported Compose `animateFloat`. Adjust import to `androidx.compose.animation.core.animateFloat`.

- [ ] **Step 1: Create `ChapterCacheBadge.kt`.**
- [ ] **Step 2: Add `cacheState: ChapterCacheState = ChapterCacheState.None` field to `ChapterCardState` in `ChapterCard.kt`.**
- [ ] **Step 3: Build.**

Run: `./gradlew :core-ui:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCacheBadge.kt \
        core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCard.kt
git commit -m "feat(ui): ChapterCacheBadge + ChapterCardState gains cacheState field

ChapterCacheBadge: Material Icons HourglassTop (Partial, pulsing
0.4 ↔ 1.0 alpha over 1.4 s round-trip) and Bolt (Complete, solid).
None state renders nothing. semantics contentDescription for a11y.

ChapterCardState gains cacheState: ChapterCacheState = None for
back-compat — existing call sites compile unchanged with the
default. PR-H's next commit wires the badge into the card layout
+ FictionDetailScreen feeds real values."
```

---

### Task H3: Render the badge inside `ChapterCard`

**Files:**
- Modify: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCard.kt`

The existing `ChapterCard` is a Row of (number, title-column, downloaded-icon, finished-icon). Insert the cache badge between the title column and the downloaded icon — clusters the "what's the state of this chapter" indicators.

Find the existing card-row structure:

```kotlin
Row(
    modifier = Modifier.padding(spacing.md),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
) {
    Text(/* number */)
    Column(modifier = Modifier.weight(1f)) {
        Text(/* title */)
        // ... published, duration ...
    }
    // existing isDownloaded / isFinished icons here
}
```

Insert the badge:

```kotlin
Row(/* same modifiers */) {
    Text(/* number */)
    Column(modifier = Modifier.weight(1f)) { /* title etc. */ }
    ChapterCacheBadge(state = state.cacheState)
    if (state.isDownloaded) {
        Icon(/* existing OfflineBolt */)
    }
    if (state.isFinished) {
        Icon(/* existing CheckCircle */)
    }
}
```

The badge sits before the downloaded/finished icons — visual order: cache state → downloaded → finished. Width: 20.dp + spacing handled by the existing `Arrangement.spacedBy(spacing.sm)`.

Update the card's `contentDescription`:

```kotlin
contentDescription = "Chapter ${state.number}, ${state.title}, ${state.durationLabel}" +
    when (state.cacheState) {
        ChapterCacheState.Complete -> ", cached, plays instantly"
        ChapterCacheState.Partial -> ", caching in progress"
        ChapterCacheState.None -> ""
    } +
    if (state.isDownloaded) ", downloaded" else ""
```

- [ ] **Step 1: Modify `ChapterCard.kt`.**
- [ ] **Step 2: Build.**

Run: `./gradlew :core-ui:assembleDebug :feature:assembleDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run preview tests if they exist.**

Run: `./gradlew :core-ui:testDebugUnitTest`
Expected: ALL PASS — `PreviewProviders.kt`'s `ChapterPreviewProvider` may need updating; if it uses the default `cacheState = None`, no change needed. If you want previews to show all three states, add three preview chapters with different states.

- [ ] **Step 4: Commit.**

```bash
git add core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/ChapterCard.kt \
        core-ui/src/main/kotlin/in/jphe/storyvox/ui/preview/PreviewProviders.kt
git commit -m "feat(ui): ChapterCard renders cache state badge

Cache badge sits between title column and the existing downloaded /
finished icons — visual order is cache state → downloaded → finished.
contentDescription expanded to include cache state for screen
readers ('cached, plays instantly' / 'caching in progress').

Preview providers gain a chapter-with-cache-Complete and
chapter-with-cache-Partial entry so the design-time preview pane
shows the badge in all three states."
```

---

### Task H4: Wire chapter-list cache state in `FictionDetailViewModel` + Screen

**Files:**
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailViewModel.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailScreen.kt`

`UiChapter` gains a `cacheState`:

```kotlin
data class UiChapter(
    val id: String,
    val number: Int,
    val title: String,
    // ... existing fields ...
    val isDownloaded: Boolean,
    val isFinished: Boolean,
    /** PCM cache state under the user's currently-active voice. */
    val cacheState: ChapterCacheState = ChapterCacheState.None,
)
```

(With `import \`in\`.jphe.storyvox.playback.cache.ChapterCacheState`.)

`FictionDetailViewModel` injects `CacheStateInspector` + `VoiceManager` (probably already injected for some other purpose; if not, inject now). Combines a flow of cache states keyed by chapterId into the chapter list:

```kotlin
class FictionDetailViewModel @Inject constructor(
    // ... existing deps ...
    private val cacheStateInspector: CacheStateInspector,
    private val voiceManager: VoiceManager,
) : ViewModel() {

    private val _state = MutableStateFlow(/* existing initial */)
    val state: StateFlow<FictionDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Combine fiction details + active voice + per-chapter cache state.
            // ChapterStates flow is recomputed when the chapter list or voice
            // changes; the resulting Map<chapterId, ChapterCacheState> is
            // applied to UiChapter projections.
            combine(
                fictionRepo.observeFiction(fictionId).filterNotNull(),
                voiceManager.activeVoice,
            ) { fiction, voice ->
                fiction to voice
            }.collectLatest { (fiction, voice) ->
                val chapterStates: Map<String, ChapterCacheState> = if (voice != null) {
                    cacheStateInspector.chapterStatesFor(
                        chapterIds = fiction.chapters.map { it.id },
                        voiceId = voice.id,
                        chunkerVersion = CHUNKER_VERSION,
                    )
                } else emptyMap()

                _state.update { current ->
                    current.copy(
                        fiction = fiction.toUi(),
                        chapters = fiction.chapters.map { ch ->
                            ch.toUi(
                                cacheState = chapterStates[ch.id] ?: ChapterCacheState.None,
                            )
                        },
                    )
                }
            }
        }
    }

    // existing listen() method unchanged

    fun clearFictionCache(fictionId: String) {
        viewModelScope.launch {
            val chapterIds = _state.value.chapters.map { it.id }
            for (id in chapterIds) {
                runCatching { pcmCache.deleteAllForChapter(id) }
            }
            // Bump cache state to None — re-poll inspector for fresh snapshot.
            val voice = voiceManager.activeVoice.first()
            val refreshed = if (voice != null) {
                cacheStateInspector.chapterStatesFor(chapterIds, voice.id, CHUNKER_VERSION)
            } else emptyMap()
            _state.update { current ->
                current.copy(
                    chapters = current.chapters.map { ch ->
                        ch.copy(cacheState = refreshed[ch.id] ?: ChapterCacheState.None)
                    },
                )
            }
        }
    }
}
```

Imports include `PcmCache`, `CacheStateInspector`, `ChapterCacheState`, `CHUNKER_VERSION`. Inject `PcmCache` if it's not already in the view-model.

`FictionDetailScreen.kt` — `UiChapter.toCardState`:

```kotlin
private fun UiChapter.toCardState(currentId: String?) = ChapterCardState(
    number = number,
    title = title,
    publishedRelative = publishedRelative,
    durationLabel = durationLabel,
    isDownloaded = isDownloaded,
    isFinished = isFinished,
    isCurrent = id == currentId,
    cacheState = cacheState,
)
```

(One-line addition.)

- [ ] **Step 1: Update `UiChapter`** in `UiContracts.kt`.
- [ ] **Step 2: Update `FictionDetailViewModel`** to inject + combine cache state.
- [ ] **Step 3: Update `toUi(cacheState)`** mapping function (likely in `FictionDetailViewModel.kt` or a sibling mapper file). If `toUi()` doesn't currently accept `cacheState`, add a defaulted parameter.
- [ ] **Step 4: Update `toCardState`** to forward `cacheState`.
- [ ] **Step 5: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run tests.**

Run: `./gradlew :feature:testDebugUnitTest`
Expected: ALL PASS — pre-existing tests pass `cacheState = None` defaults.

- [ ] **Step 7: Commit.**

```bash
git add feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailViewModel.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailScreen.kt
git commit -m "feat(fiction): chapter list shows per-chapter PCM cache state

UiChapter gains cacheState: ChapterCacheState. FictionDetailViewModel
combines fictionRepo.observeFiction + voiceManager.activeVoice into
a flow that, on every emission, queries CacheStateInspector for the
batch chapter states and propagates into the chapter list.

ChapterCardState.cacheState wired through. ChapterCard renders the
badge (PR-H Task H3). Result: chapter list visibly shows None /
Partial / Complete per chapter."
```

---

### Task H5: "Clear fiction cache" overflow menu action

**Files:**
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailScreen.kt`

Add an overflow menu (top-right kebab) to `FictionDetailScreen` if it doesn't already exist. If a `TopAppBar` is in place, add an `IconButton` with a dropdown menu containing "Clear fiction cache".

If the screen doesn't currently have a top bar, the cleanest add is a small `OverflowMenu` composable in the existing `Hero` or `BottomBar` layout. Pick whichever fits the established pattern — likely the existing `BottomBar` already has space alongside Listen / Library actions.

Conservative add — a "Clear fiction cache" `BrassButton` in the existing `BottomBar` row, behind a small overflow:

```kotlin
@Composable
private fun ClearFictionCacheAction(
    fictionTitle: String,
    onClear: () -> Unit,
) {
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Clear fiction cache") },
                onClick = {
                    menuExpanded = false
                    showConfirm = true
                },
                leadingIcon = {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                },
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear cached audio for this fiction?") },
            text = {
                Text(
                    "Removes the PCM cache for every chapter of \"$fictionTitle\". Replays will re-render once. The fiction stays in your library; reading positions are not affected.",
                )
            },
            confirmButton = {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onClear()
                        showConfirm = false
                    },
                    variant = BrassButtonVariant.Destructive,
                )
            },
            dismissButton = {
                BrassButton(
                    label = "Cancel",
                    onClick = { showConfirm = false },
                    variant = BrassButtonVariant.Secondary,
                )
            },
        )
    }
}
```

Wire into `FictionDetailScreen`'s top-level composable:

```kotlin
ClearFictionCacheAction(
    fictionTitle = state.fiction.title,
    onClear = { viewModel.clearFictionCache(fictionId) },
)
```

Place near the existing top-area actions (Library +/- toggle, etc.). If the screen has no `TopAppBar`, add a minimal `Row` at the top of the content with the overflow icon at the trailing edge.

- [ ] **Step 1: Add the composable.**
- [ ] **Step 2: Wire `viewModel.clearFictionCache(fictionId)` from `FictionDetailViewModel`** (added in Task H4).
- [ ] **Step 3: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add feature/src/main/kotlin/in/jphe/storyvox/feature/fiction/FictionDetailScreen.kt
git commit -m "feat(fiction): Clear fiction cache action

Overflow menu (kebab + DropdownMenu) on FictionDetailScreen with one
entry: 'Clear fiction cache'. Confirmation AlertDialog with
fiction-title-bearing copy. On confirm, viewModel.clearFictionCache
sweeps every chapterId via PcmCache.deleteAllForChapter and re-polls
CacheStateInspector to refresh badges."
```

---

### Task H6: Voice library per-voice cached-MB indicator

**Files:**
- Modify: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/UiVoiceInfo.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryViewModel.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryScreen.kt`

`UiVoiceInfo` add a `cachedBytes`:

```kotlin
data class UiVoiceInfo(
    // ... existing fields ...
    val cachedBytes: Long = 0L,
)
```

`VoiceLibraryViewModel` injects `CacheStateInspector` + computes the per-voice byte map on every `installedVoices` flow emission:

```kotlin
class VoiceLibraryViewModel @Inject constructor(
    // ... existing ...
    private val inspector: CacheStateInspector,
) : ViewModel() {

    init {
        viewModelScope.launch {
            // Existing flow that emits installed/available voices...
            voiceManager.installedVoices.collectLatest { installed ->
                val cached = runCatching { inspector.bytesUsedByEveryVoice() }
                    .getOrDefault(emptyMap())
                _state.update { current ->
                    current.copy(
                        installedByEngine = installed.mapValues { (_, byTier) ->
                            byTier.mapValues { (_, voices) ->
                                voices.map { v ->
                                    v.copy(cachedBytes = cached[v.id] ?: 0L)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
```

`VoiceLibraryScreen.kt`'s `VoiceRow` adds the line:

Find the existing voice-row layout (around line 380 in the file). After the existing duration/quality subtitle line, add:

```kotlin
if (voice.cachedBytes > 0) {
    Text(
        formatBytes(voice.cachedBytes) + " cached",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

Imports `formatBytes` from `feature.api.UiContracts` (defined in PR-G).

- [ ] **Step 1: Update `UiVoiceInfo`.**
- [ ] **Step 2: Update `VoiceLibraryViewModel` to compute the cached map.**
- [ ] **Step 3: Update `VoiceRow` in `VoiceLibraryScreen`.**
- [ ] **Step 4: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/UiVoiceInfo.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryViewModel.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryScreen.kt
git commit -m "feat(voicelibrary): per-voice cached-MB indicator

UiVoiceInfo gains cachedBytes: Long (default 0). VoiceLibraryViewModel
computes the per-voice byte map once per installed-voices emission via
CacheStateInspector.bytesUsedByEveryVoice(). VoiceRow renders 'X MB
cached' as a labelSmall subtitle when cachedBytes > 0; voices the user
hasn't actually listened to don't get the line.

Lets users see at a glance which voices have actually been used —
useful for the 'I've installed 5 voices, which ones do I keep?' decision."
```

---

### Task H7: `CacheStateInspector` test

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspectorTest.kt`

Robolectric. Verifies the three classifications and the per-voice byte sum.

```kotlin
package `in`.jphe.storyvox.playback.cache

import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CacheStateInspectorTest {

    private lateinit var cache: PcmCache
    private lateinit var inspector: CacheStateInspector
    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        cache = PcmCache(ctx, PcmCacheConfig(ctx))
        inspector = CacheStateInspector(cache)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private fun renderComplete(chapterId: String, voiceId: String, bytes: Int) {
        val key = PcmCacheKey(chapterId, voiceId, 100, 100, CHUNKER_VERSION)
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(Sentence(0, 0, 5, "S."), ByteArray(bytes), trailingSilenceMs = 350)
        app.finalize()
    }

    private fun renderPartial(chapterId: String, voiceId: String, bytes: Int) {
        val key = PcmCacheKey(chapterId, voiceId, 100, 100, CHUNKER_VERSION)
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(Sentence(0, 0, 5, "S."), ByteArray(bytes), trailingSilenceMs = 350)
        // No finalize → meta + pcm exist, idx absent → Partial
    }

    @Test
    fun `chapterStateFor returns Complete for finalized entries`() = runBlocking {
        renderComplete("ch1", "cori", 100)
        val state = inspector.chapterStateFor("ch1", "cori", CHUNKER_VERSION)
        assertEquals(ChapterCacheState.Complete, state)
    }

    @Test
    fun `chapterStateFor returns Partial when meta exists but idx does not`() = runBlocking {
        renderPartial("ch1", "cori", 100)
        val state = inspector.chapterStateFor("ch1", "cori", CHUNKER_VERSION)
        assertEquals(ChapterCacheState.Partial, state)
    }

    @Test
    fun `chapterStateFor returns None for unknown keys`() = runBlocking {
        val state = inspector.chapterStateFor("ch-nonexistent", "cori", CHUNKER_VERSION)
        assertEquals(ChapterCacheState.None, state)
    }

    @Test
    fun `chapterStatesFor batches efficiently`() = runBlocking {
        renderComplete("ch1", "cori", 100)
        renderPartial("ch2", "cori", 100)

        val states = inspector.chapterStatesFor(
            chapterIds = listOf("ch1", "ch2", "ch3"),
            voiceId = "cori",
            chunkerVersion = CHUNKER_VERSION,
        )
        assertEquals(ChapterCacheState.Complete, states["ch1"])
        assertEquals(ChapterCacheState.Partial, states["ch2"])
        assertEquals(ChapterCacheState.None, states["ch3"])
    }

    @Test
    fun `bytesUsedByVoice sums pcm files matching the voice`() = runBlocking {
        renderComplete("ch1", "cori", 1_000)
        renderComplete("ch2", "cori", 2_500)
        renderComplete("ch3", "amy",  500)

        assertEquals(3_500L, inspector.bytesUsedByVoice("cori"))
        assertEquals(500L, inspector.bytesUsedByVoice("amy"))
        assertEquals(0L, inspector.bytesUsedByVoice("nonexistent"))
    }

    @Test
    fun `bytesUsedByEveryVoice returns map of all voices`() = runBlocking {
        renderComplete("ch1", "cori", 1_000)
        renderComplete("ch2", "cori", 2_500)
        renderComplete("ch3", "amy",  500)

        val all = inspector.bytesUsedByEveryVoice()
        assertEquals(3_500L, all["cori"])
        assertEquals(500L, all["amy"])
        assertEquals(2, all.size)
    }

    @Test
    fun `partial entries also count in bytesUsedByVoice`() = runBlocking {
        renderPartial("ch1", "cori", 1_000)
        // Meta exists with voiceId=cori; pcm exists at 1000 bytes.
        // bytesUsedByVoice walks meta files → matches.
        assertEquals(1_000L, inspector.bytesUsedByVoice("cori"))
    }
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Run.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*CacheStateInspectorTest*"`
Expected: ALL PASS.

- [ ] **Step 3: Run full module suite.**

Run: `./gradlew :core-playback:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStateInspectorTest.kt
git commit -m "test(playback): CacheStateInspector classification + byte sums

Robolectric-backed. Verifies:
  - chapterStateFor returns Complete for finalized entries
  - Partial when meta exists, idx doesn't
  - None for unknown keys
  - chapterStatesFor batches consistently
  - bytesUsedByVoice sums only pcm whose meta.voiceId matches
  - bytesUsedByEveryVoice returns full map
  - Partial entries (in-flight renders) also count toward voice bytes —
    consistent with the user-facing 'X MB cached' which represents
    disk used, not 'fully cached chapters'"
```

---

### Task H8: Tablet smoke-test PR-H on R83W80CAFZB

**Files:** none — runtime verification.

PR-H is the visible-polish lane; tablet testing verifies the badges look right and don't regress chapter-card layout. Per memory `feedback_install_test_each_iteration.md`.

- [ ] **Step 1: Claim tablet lock #47.**

- [ ] **Step 2: Build + install.**

- [ ] **Step 3: Chapter list cache state visualization.**

- Open a fiction with multiple chapters in mixed cache states (some played, some not, ideally one mid-render):
  - **Cached chapters** → solid lightning bolt badge.
  - **In-flight render** (PR-F's worker queued one) → pulsing hourglass.
  - **Never-played chapters** → no badge.

Verify visually: badge size (20.dp) doesn't crowd the title. Spacing between badge and existing isDownloaded / isFinished icons is consistent (the existing `Arrangement.spacedBy(spacing.sm)`).

- [ ] **Step 4: Pulsing animation visual.**

Watch a Partial-state chapter for at least 5 s. The hourglass pulses smoothly, no jank. Pulse rate ~700 ms one direction → ~1.4 s round-trip — visible but not distracting.

- [ ] **Step 5: Cache-state recompute on voice swap.**

- Open a fiction with chapters cached for voice "cori".
- Switch active voice to "amy" via Voice Library.
- Return to fiction detail. Chapter list should now show NONE for every chapter (no "amy" cache yet) — the badges flip from lightning bolts to invisible.

- [ ] **Step 6: Clear fiction cache.**

- Open fiction overflow menu → "Clear fiction cache".
- Confirmation dialog appears with fiction title in body text. Tap Clear.
- Within seconds, every chapter's badge in the list flips from Complete (or Partial) to None.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls cache/pcm-cache/
```

Expected: only files for OTHER fictions remain.

- [ ] **Step 7: Voice library cached-MB indicator.**

- Open Voice Library. The voice the user has actively played (e.g. "cori") shows "X MB cached" subtitle (e.g. "1.2 GB cached" or "47 MB cached" — depends on history).
- Voices the user hasn't played show no cached-MB line.

- [ ] **Step 8: Capture screenshots.**

```bash
adb -s R83W80CAFZB shell screencap -p /sdcard/chapter-list-pr-h.png
adb -s R83W80CAFZB shell screencap -p /sdcard/voice-library-pr-h.png
adb -s R83W80CAFZB shell screencap -p /sdcard/clear-fiction-confirm.png
```

Pull all to `~/.claude/projects/-home-jp/scratch/<voice>-pcm-cache-pr-h/`.

- [ ] **Step 9: Release tablet lock.**

- [ ] **Step 10: Push.**

```bash
git push -u origin dream/<voice>/pcm-cache-pr-h
```

---

### Task H9: Open PR-H

```bash
gh pr create --base main --head dream/<voice>/pcm-cache-pr-h \
  --title "feat(ui): PCM cache status icons + UX polish (PR-H)" \
  --body "$(cat <<'EOF'
## Summary

PR-H of the chapter PCM cache. Closes the series with visible-to-
user polish:

- **Per-chapter cache state badge** in the chapter list. Three states:
  None (invisible), Partial (pulsing hourglass while rendering),
  Complete (solid lightning bolt). Wired through ChapterCardState +
  ChapterCacheBadge.
- **Voice library cached-MB indicator** per voice row. 'X MB cached'
  subtitle when the voice has any cached PCM; lets users see which
  voices they've actually used.
- **Clear fiction cache** action in Fiction Detail overflow menu.
  Confirmation dialog gates the destructive action; sweeps every
  chapterId via PcmCache.deleteAllForChapter.

## Architecture

`CacheStateInspector` is the read-only seam between PcmCache and the
UI:
- `chapterStateFor(chapterId, voiceId, chunkerVersion)` → None / Partial / Complete
- `chapterStatesFor(ids, ...)` for batch (chapter list)
- `bytesUsedByVoice(voiceId)` and `bytesUsedByEveryVoice()` for voice library

Computed on screen-enter and on voice-swap; no caching needed
(directory walk + JSON parses are sub-millisecond on internal flash).

## Test plan

- [x] CacheStateInspectorTest (Robolectric):
  - chapterStateFor: Complete / Partial / None
  - chapterStatesFor batches correctly
  - bytesUsedByVoice sums only matching meta
  - bytesUsedByEveryVoice returns full map
  - Partial entries count toward bytes (in-flight = disk used)
- [x] R83W80CAFZB:
  - Chapter list shows three badge states correctly
  - Pulsing animation on Partial is smooth
  - Voice swap recomputes cache state per chapter
  - Clear fiction cache → badges flip to None across the list
  - Voice library shows 'X MB cached' for played voices only
- [x] `./gradlew :app:assembleDebug` green
- [x] `./gradlew :core-playback:testDebugUnitTest` green
- [x] `./gradlew :feature:testDebugUnitTest` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Same Copilot review + capped wait + squash-merge.

After PR-H merges, the **PCM cache series is complete**. Orchestrator may bump version to v0.5.0 and ship — JP's "high-quality voices play smoothly on slow devices" success criterion holds for cached chapters (instant + gapless), and Mode C makes EVERY library chapter cached for users who want the gapless-everywhere experience.

---

## Open questions for JP

1. **Icon choice — Bolt vs FilledDisc vs CloudDone.** Plan picks `Icons.Filled.Bolt` (lightning) for Complete because it visually conveys "instant" — matches the user's experience of "tap play, audio starts now". Alternatives: filled disc icon (literal "data on disk"), or `CloudDone` (familiar from streaming apps for "downloaded for offline"). **Recommendation: Bolt for instant-play UX framing; if JP prefers a different icon, swap is one line in `ChapterCacheBadge.kt`.**

2. **Pulse rate for Partial.** Plan picks 700 ms one-direction → 1.4 s round-trip. Subtle, not distracting. **Confirm or adjust.**

3. **Cache state for non-default speed/pitch.** Plan inspects only the (chapter, voice, 1.0×, 1.0×) key. A user playing at 1.25× has a separate cache file the badge doesn't see → the badge says "None" or "Partial" even though the user's-current-speed cache might be Complete. **Recommendation: ship as-is; the 1.0× cache is the most likely state for new chapters because PR-F's worker renders at 1.0×. If a user lives at 1.25×, the badge will lag their actual cache state until they trigger a 1.25× render. Acceptable v0.5.0 polish.** Alternative: inspect the user's CURRENT speed/pitch from EnginePlayer state. Adds a flow dependency; defer.

4. **Per-fiction overflow menu placement.** Plan adds a kebab DropdownMenu — depends on whether `FictionDetailScreen` already has a TopAppBar. If it doesn't, the kebab sits inside the existing `BottomBar` row or at the top of the `Hero` block. **Verify in code; pick the cleanest visual home and document in the commit message.**

5. **"Clear fiction cache" + actively playing chapter.** If the user clears a fiction's cache while one of its chapters is playing, the streaming source's `cacheAppender` (PR-D) is overwriting a key whose underlying files we just deleted. PR-D's appender re-creates the files on next `appendSentence`. Behavior: clean (the appender is robust to a wipe-out under it). **No action needed; document in PR description.**

6. **Voice-library byte indicator vs disk-Used in Settings.** Settings shows total cache disk used; Voice Library shows per-voice. Both can be visible at once (across screens). Numbers should sum to (~) Settings total — minor discrepancy if a meta.json is unparseable (rare). **Acceptable.**

---

## Self-review

**Spec coverage check (PR-H scope from spec line 413):**
- ✓ "Per-chapter cache status icon" → ChapterCacheBadge + integration
- ✓ "'Clear cache' affordance" — interpreted as both global (PR-G's button) AND per-fiction (PR-H's overflow). Spec is short; both are reasonable surfaces.
- (Plus voice library polish, which goes beyond strict spec scope but rounds out the "cache as user-visible feature" UX. JP can scope down if needed.)

**Spec deltas / decisions:**
- **Three states (None / Partial / Complete) vs binary (cached / not).** Plan picks three because Partial has a real visual difference (pulsing), and a render in flight is genuinely useful info — "give it a minute, it'll be ready". Spec doesn't specify; this is plan judgment.
- **Per-fiction Clear** — spec line 413 says just "'Clear cache' affordance" (singular). PR-G's global Clear satisfies the literal spec; PR-H's per-fiction is additive polish. **Confirmed scope creep, but small and obviously useful.**
- **Voice library indicator** — beyond strict spec scope but trivially small implementation given `CacheStateInspector.bytesUsedByEveryVoice`. **Confirmed scope creep, defensible.**

**Placeholder scan:** None — every Compose snippet uses existing components.

**Type consistency:** `ChapterCacheState` enum lives in `core-playback`, imported by `core-ui` and `feature` — both modules already depend on `core-playback`. `CacheStateInspector` exposes both single-key and batch APIs to match UI use cases.

**Risks deferred:**
- Animated badge transitions (None → Partial → Complete) — static state badges only.
- Voice-library "render queue" indicator — punted.
- "Pin this fiction" affordance — punted.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-h.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute H1-H9 inline. PR-open is the JP-visible boundary; everything before that stays in-session.

After H merges, the cache series is COMPLETE. Orchestrator handles the v0.5.0 release bundling; PR-H is the last code PR in the chain.
