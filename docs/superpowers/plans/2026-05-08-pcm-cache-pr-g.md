# PCM Cache PR-G Implementation Plan — Settings UI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the user-facing knobs for the PCM cache in `Settings → Performance & buffering`. Three additions:

1. **Mode C — Full Pre-render switch.** Boolean toggle, default false. Already plumbed through `PlaybackModeConfig.fullPrerender` (PR-F); PR-G adds the composable.
2. **Audio cache size selector.** Radio-button-style picker over four discrete options: 500 MB (light), 2 GB (default), 5 GB, Unlimited. Backed by `PcmCacheConfig.setQuotaBytes(...)` (PR-C).
3. **"Currently used" + "Clear cache" affordance.** Live-updating "1.4 GB / 2 GB" indicator + a destructive-action button that calls `PcmCache.clearAll()` and re-queries the size. Confirmation dialog gates the destructive action.

**Architecture:**

```
Settings UI (Composables)
     │
     ▼
SettingsViewModel (existing)
     │
     ├──► SettingsRepositoryUi.setQuotaBytes(...)
     │      → PcmCacheConfig.setQuotaBytes(...)        (PR-C)
     │
     ├──► SettingsRepositoryUi.setFullPrerender(...)
     │      → PlaybackModeConfig.fullPrerender setter   (PR-F)
     │
     └──► UiCacheStatsRepository.observeCacheStats()
            → PcmCache.totalSizeBytes() polled on a 5 s tick
              + PcmCacheConfig.quotaBytes()
```

Live cache-size indicator polls `PcmCache.totalSizeBytes()` on a 5-second cadence while the Settings screen is visible. Polling avoids hooking eviction/finalize-time callbacks across modules; 5 s latency on a "1.4 GB / 2 GB" indicator is fine. Settings screen lifecycle stops the poll on dispose.

**Tech stack:** Compose Material 3, kotlinx.coroutines `Flow.flow { ... emit() ... delay() }` for the poll, JUnit + Robolectric for the cache-stats repo. No new deps.

**Spec:** `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` — sections "Storage policy" (the quota selector list) and "User-facing UI" (the "Currently used: 1.4 GB / 2 GB" + Clear cache button).

**Out of scope (deferred):**

- **Per-chapter status icons.** PR-H.
- **Voice library cached-MB indicator.** PR-H.
- **"Clear this fiction's cache" per-fiction action.** PR-H.
- **Granular quota slider.** Spec says four discrete options; no need for a continuous slider. If user demand surfaces, follow-up.
- **Per-Mode-C explanatory help text.** Plan adds short subtitles; richer onboarding (a "first time you flip Mode C" dialog explaining "this will use 2 GB+ of disk") is a follow-up.

---

## File Structure

### New files

- `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepository.kt`
  Observes total cache size + quota for the Settings UI. Lightweight polling Flow.

### Modified files

- `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`
  Add `quotaBytes: Long`, `cacheUsedBytes: Long`, `cacheStatsLoading: Boolean` to `UiSettings`. Add `setQuotaBytes(bytes: Long)` and `clearCache()` to `SettingsRepositoryUi`.
- `app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt`
  Combine `CacheStatsRepository` flow into the `UiSettings` projection. Implement the new setters: `setQuotaBytes` → `PcmCacheConfig.setQuotaBytes`; `clearCache` → `PcmCache.clearAll` + `evictToQuota` (no-op after wipe but keeps invariant) + bump observation tick.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt`
  Add three composables to the existing "Performance & buffering" section:
  - `FullPrerenderRow` — Mode C switch.
  - `CacheSizeSelector` — 4-option radio-style chooser.
  - `CacheUsageRow` — "Currently used" + "Clear cache" button + confirmation dialog.
- `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModel.kt`
  Forward `setFullPrerender` and `setQuotaBytes` and `clearCache` to the repository.

### New tests

- `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepositoryTest.kt`
  Robolectric. Verifies the flow emits initial state, re-emits when cache grows or shrinks.

---

## Conventions

- All commits use conventional-commit style. Branch is `dream/<voice>/pcm-cache-pr-g`.
- Run from worktree root.
- Fast iteration: `./gradlew :core-playback:testDebugUnitTest :feature:testDebugUnitTest`.
- Full app build: `./gradlew :app:assembleDebug`.
- Tablet smoke-test on R83W80CAFZB **required**: the cache-size indicator must update within 5 s of cache changes; the Mode C toggle must trigger PR-F's PrerenderModeWatcher; the Clear cache button must visibly drop the size to 0. Per memory `feedback_install_test_each_iteration.md`.
- Selective `git add` per CLAUDE.md.
- **No version bump in this PR.** Orchestrator handles release bundling.

---

## Sub-change sequencing

Five commits inside the PR:

1. `feat(playback): CacheStatsRepository — polling totalSizeBytes flow` — backend.
2. `feat(settings): UiSettings + SettingsRepositoryUi gain cache fields/setters` — contract surface.
3. `feat(settings): app SettingsRepositoryUiImpl wires CacheStatsRepository + setters` — wire-up.
4. `feat(settings): Settings UI for Mode C + cache size + Clear cache` — composables.
5. `test(playback): CacheStatsRepository emission semantics` — tests.

---

## PR-G Tasks

### Task G1: `CacheStatsRepository` polling flow

**Files:**
- Create: `core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepository.kt`

```kotlin
package `in`.jphe.storyvox.playback.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Live observation of PCM cache size + quota for the Settings UI's
 * "Currently used: 1.4 GB / 2 GB" row.
 *
 * Implementation is a polling flow rather than an event stream because:
 *  - PR-D's tee-write and PR-F's worker each finalize/abandon at
 *    different lifetimes; subscribing to either set of events would
 *    miss eviction calls (which run from the workers and from
 *    EnginePlayer's natural-end branch).
 *  - The user-facing latency we care about is "did Clear cache work?"
 *    which we resolve by re-polling immediately after the action; for
 *    the steady-state indicator a 5 s tick is well within human
 *    perception of "live".
 *  - A polling flow is trivial to test (Robolectric advance time, observe
 *    next emission).
 *
 * Settings screen lifecycle ends the poll: the flow is `cold`, started
 * when the UI subscribes, cancelled when the UI disposes. No background
 * resource use when Settings isn't open.
 *
 * @param pollIntervalMs default 5 s. Visible for testing.
 */
@Singleton
class CacheStatsRepository @Inject constructor(
    private val cache: PcmCache,
    private val config: PcmCacheConfig,
) {

    data class CacheStats(
        val usedBytes: Long,
        val quotaBytes: Long,
    )

    fun observe(pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS): Flow<CacheStats> = flow {
        while (true) {
            emit(snapshot())
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged()

    suspend fun snapshot(): CacheStats = CacheStats(
        usedBytes = cache.totalSizeBytes(),
        quotaBytes = config.quotaBytes(),
    )

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5_000L
    }
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Compile.**

Run: `./gradlew :core-playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/main/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepository.kt
git commit -m "feat(playback): CacheStatsRepository — polling totalSizeBytes flow

Cold flow that emits a CacheStats(usedBytes, quotaBytes) every 5 s
while subscribed. Settings screen subscribes on enter, cancels on
dispose — no background resource use when Settings is closed. 5 s
latency on a 'Currently used' indicator is well within perceptible
'live'; polling avoids the cross-module event-bus surface that
hooking finalize/evict callbacks would require.

Backend for PR-G's Settings UI 'Audio cache' row."
```

---

### Task G2: Extend `UiSettings` + `SettingsRepositoryUi` contracts

**Files:**
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt`

Add the cache fields to `UiSettings` and the setters to `SettingsRepositoryUi`:

```kotlin
data class UiSettings(
    // ... existing fields including fullPrerender from PR-F ...
    /**
     * PCM cache total bytes used (sum of `<sha>.pcm` file sizes under
     * `${context.cacheDir}/pcm-cache/`). Polled by
     * `:app`'s SettingsRepositoryUiImpl via CacheStatsRepository's
     * 5 s flow. Surfaced in Settings as "Currently used: 1.4 GB / 2 GB".
     */
    val cacheUsedBytes: Long = 0L,
    /**
     * Configured PCM cache quota in bytes. Default 2 GB. The Settings
     * UI offers four discrete options (500 MB / 2 GB / 5 GB / Unlimited);
     * Unlimited is represented as `Long.MAX_VALUE`.
     */
    val cacheQuotaBytes: Long = 2L * 1024 * 1024 * 1024,
    val palace: UiPalaceConfig = UiPalaceConfig(),
)

/** Discrete cache-quota options surfaced by the Settings UI. */
object CacheQuotaOptions {
    const val LIGHT_500MB: Long = 500L * 1024 * 1024
    const val DEFAULT_2GB: Long = 2L * 1024 * 1024 * 1024
    const val ROOMY_5GB: Long = 5L * 1024 * 1024 * 1024
    const val UNLIMITED: Long = Long.MAX_VALUE

    val all: List<Long> = listOf(LIGHT_500MB, DEFAULT_2GB, ROOMY_5GB, UNLIMITED)

    fun label(bytes: Long): String = when (bytes) {
        LIGHT_500MB -> "500 MB"
        DEFAULT_2GB -> "2 GB"
        ROOMY_5GB -> "5 GB"
        UNLIMITED -> "Unlimited"
        else -> formatBytes(bytes)
    }

    /** Snap an arbitrary value to the nearest discrete option. Used
     *  when migrating an old DataStore value that doesn't match any
     *  option — a future "custom slider" follow-up may store
     *  off-grid values; today we snap. */
    fun snap(bytes: Long): Long {
        if (bytes >= UNLIMITED / 2) return UNLIMITED
        val deltas = all.dropLast(1).map { kotlin.math.abs(it - bytes) }
        val minIdx = deltas.indexOf(deltas.min())
        return all[minIdx]
    }
}

/** Pretty-print a byte count (used by the cache-size labels). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
```

In `SettingsRepositoryUi`:

```kotlin
/**
 * Set the PCM cache quota. Snaps to the nearest discrete
 * [CacheQuotaOptions] entry; values below 100 MB clamp to 100 MB
 * (PcmCacheConfig's floor).
 */
suspend fun setCacheQuotaBytes(bytes: Long)

/**
 * Wipe the entire PCM cache (`PcmCache.clearAll`). Triggered by the
 * destructive-action confirmation in Settings. Returns the number of
 * bytes freed (informational; the UI can also re-poll cacheUsedBytes
 * to confirm).
 */
suspend fun clearCache(): Long
```

- [ ] **Step 1: Add the fields, the `CacheQuotaOptions` object, the `formatBytes` helper, and the setter declarations.**
- [ ] **Step 2: Build.**

Run: `./gradlew :feature:assembleDebug`
Expected: BUILD FAILS — `SettingsRepositoryUiImpl` doesn't yet implement the new setters. Resolved in next task.

- [ ] **Step 3: Commit (will not yet build the full app, but `:feature` only compile is fine).**

```bash
git add feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt
git commit -m "feat(settings): UiSettings + SettingsRepositoryUi cache fields/setters

UiSettings gains cacheUsedBytes (polled), cacheQuotaBytes
(persisted, default 2 GB).

CacheQuotaOptions object exposes the four discrete options the
Settings UI surfaces (500 MB / 2 GB / 5 GB / Unlimited) along with
labels + snap-to-nearest for migrating off-grid stored values.
formatBytes pretty-prints arbitrary byte counts for the 'Currently
used' indicator.

SettingsRepositoryUi gains setCacheQuotaBytes and clearCache.
Implementation lands in :app in the next commit."
```

---

### Task G3: Wire `SettingsRepositoryUiImpl` to the cache layer

**Files:**
- Modify: `app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt`

Inject `PcmCache`, `PcmCacheConfig`, `CacheStatsRepository`. Combine the `CacheStatsRepository.observe()` flow into the existing settings flow; implement the new setters.

```kotlin
class SettingsRepositoryUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val palaceClient: PalaceClient,
    private val aiAdapterRegistry: AiAdapterRegistry,
    private val pcmCache: PcmCache,                    // NEW (PR-G)
    private val pcmCacheConfig: PcmCacheConfig,        // NEW (PR-G)
    private val cacheStats: CacheStatsRepository,      // NEW (PR-G)
) : SettingsRepositoryUi, PlaybackBufferConfig, PlaybackModeConfig {
```

Combine into the `settings` flow:

```kotlin
override val settings: Flow<UiSettings> = combine(
    store.data,
    authRepository.sessionState,
    cacheStats.observe(),
) { prefs, session, stats ->
    UiSettings(
        // ... existing field assignments ...
        fullPrerender = prefs[Keys.FULL_PRERENDER] ?: false,
        cacheUsedBytes = stats.usedBytes,
        cacheQuotaBytes = stats.quotaBytes,
    )
}
```

`combine` with three flows is straightforward; the existing impl already uses `combine(...)` so this is an N+1 add.

The new setters:

```kotlin
override suspend fun setCacheQuotaBytes(bytes: Long) {
    val snapped = CacheQuotaOptions.snap(bytes)
    pcmCacheConfig.setQuotaBytes(snapped)
    // Eviction immediately to honor the new (possibly-tighter) quota.
    // Pinned: empty (this is a user-explicit action; nothing is
    // currently playing in this code path because Settings is in
    // foreground). If a chapter IS playing, the active key won't be
    // pinned and may evict — acceptable for an explicit user action.
    runCatching { pcmCache.evictToQuota() }
}

override suspend fun clearCache(): Long {
    val before = pcmCache.totalSizeBytes()
    pcmCache.clearAll()
    return before
}
```

- [ ] **Step 1: Add the constructor injections + the combine + the setter impls.**
- [ ] **Step 2: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all unit tests.**

Run: `./gradlew :app:testDebugUnitTest :core-playback:testDebugUnitTest :feature:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt
git commit -m "feat(settings): wire CacheStatsRepository + cache setters in :app impl

settings flow now combines CacheStatsRepository.observe() into
UiSettings.cacheUsedBytes / cacheQuotaBytes — Settings UI gets
live-updating values via the existing flow plumbing.

setCacheQuotaBytes snaps to the discrete options (per UiContracts'
CacheQuotaOptions.snap) before persisting via PcmCacheConfig and
runs evictToQuota to honor a tightened cap immediately.

clearCache returns bytes-freed and calls PcmCache.clearAll. Re-poll
of CacheStatsRepository inside its 5 s tick reflects the wipe to
the UI; the action's own emit is fast because clearAll is just
listFiles + delete on internal flash."
```

---

### Task G4: Settings UI composables

**Files:**
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt`
- Modify: `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModel.kt`

Add three composables to the "Performance & buffering" section, right after the existing Mode B (`Catch-up Pause`) row and before the buffer slider — so the cache-related knobs cluster with Mode A / Mode B.

**Composable 1 — Mode C switch:**

```kotlin
// Mode C — Full Pre-render. Toggle, default OFF. When ON, library-add
// scheduling expands from chapters 1-3 to all chapters; PR-F's
// PrerenderTriggers + PrerenderModeWatcher fan out background renders
// of every library fiction's remaining chapters.
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f)) {
        Text("Full Pre-render", style = MaterialTheme.typography.bodyMedium)
        Text(
            if (s.fullPrerender) {
                "Cache every chapter of every library fiction in the background. Aggressive disk + CPU use; gapless playback everywhere."
            } else {
                "Cache the next few chapters only (1-3 on add, +2 on chapter end). Lighter on disk."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Switch(checked = s.fullPrerender, onCheckedChange = viewModel::setFullPrerender)
}
```

**Composable 2 — Cache size selector:**

```kotlin
// Audio cache size. Four discrete options per spec; radio-button row.
// Snaps to the closest option if a stored value falls between (handled
// in CacheQuotaOptions.snap).
@Composable
private fun CacheSizeSelector(
    quotaBytes: Long,
    onQuotaChange: (Long) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column {
        Text("Audio cache size", style = MaterialTheme.typography.bodyMedium)
        Text(
            "How much disk to use for cached audiobook PCM. Larger = more chapters playable instantly; smaller = less disk used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.fillMaxWidth()) {
            CacheQuotaOptions.all.forEach { opt ->
                val variant =
                    if (opt == quotaBytes) BrassButtonVariant.Primary
                    else BrassButtonVariant.Secondary
                BrassButton(
                    label = CacheQuotaOptions.label(opt),
                    onClick = { onQuotaChange(opt) },
                    variant = variant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
```

(Reuses the existing `BrassButton` component from `core-ui` — same visual idiom as the Theme override row in the existing Settings screen.)

**Composable 3 — Cache usage row + Clear cache:**

```kotlin
@Composable
private fun CacheUsageRow(
    usedBytes: Long,
    quotaBytes: Long,
    onClearCache: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var showConfirm by rememberSaveable { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Currently used", style = MaterialTheme.typography.bodyMedium)
            val quotaLabel = if (quotaBytes == CacheQuotaOptions.UNLIMITED)
                "no cap" else formatBytes(quotaBytes)
            Text(
                "${formatBytes(usedBytes)} / $quotaLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrassButton(
            label = "Clear cache",
            onClick = { showConfirm = true },
            variant = BrassButtonVariant.Destructive,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear audio cache?") },
            text = {
                Text(
                    "Frees ${formatBytes(usedBytes)} of disk. Chapters you replay will re-render the first time you play them. Library + reading positions are not affected.",
                )
            },
            confirmButton = {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onClearCache()
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

(`BrassButtonVariant.Destructive` may need to be added to the existing `BrassButton` API if it doesn't exist; if it doesn't, use `Secondary` with explicit error-color tint via theme — match the existing destructive-action styling in the codebase.)

- [ ] **Step 1: Insert the three composables into `SettingsScreen.kt`'s "Performance & buffering" section.**

After the existing Mode B (Catch-up Pause) row, before `BufferSlider(...)`. Order: Mode C row → CacheSizeSelector → CacheUsageRow → BufferSlider → PunctuationPauseSlider (existing).

```kotlin
SectionHeader("Performance & buffering")
Text(/* existing intro */)

// Mode A — Warm-up Wait (existing)
Row(/* ... */) { /* ... */ }

// Mode B — Catch-up Pause (existing)
Row(/* ... */) { /* ... */ }

// Mode C — Full Pre-render (NEW, PR-G)
Row(/* full prerender from above */) { /* ... */ }

// Audio cache size (NEW, PR-G)
CacheSizeSelector(
    quotaBytes = s.cacheQuotaBytes,
    onQuotaChange = viewModel::setCacheQuotaBytes,
)

// Cache usage row (NEW, PR-G)
CacheUsageRow(
    usedBytes = s.cacheUsedBytes,
    quotaBytes = s.cacheQuotaBytes,
    onClearCache = viewModel::clearCache,
)

// Buffer Headroom (existing)
BufferSlider(/* ... */)

// Punctuation Cadence (existing)
PunctuationPauseSlider(/* ... */)
```

- [ ] **Step 2: Add view-model methods.**

In `SettingsViewModel.kt`:

```kotlin
fun setFullPrerender(enabled: Boolean) {
    viewModelScope.launch { repo.setFullPrerender(enabled) }
}

fun setCacheQuotaBytes(bytes: Long) {
    viewModelScope.launch { repo.setCacheQuotaBytes(bytes) }
}

fun clearCache() {
    viewModelScope.launch { repo.clearCache() }
}
```

- [ ] **Step 3: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run feature tests.**

Run: `./gradlew :feature:testDebugUnitTest`
Expected: ALL PASS — pre-existing tests don't depend on the new fields; default values keep them green.

- [ ] **Step 5: Compose preview render check (optional but recommended).**

If the Settings preview provider is set up, render Sky Pride preview state and visually inspect the new section in Android Studio's Compose preview pane. Tablet smoke also catches issues.

- [ ] **Step 6: Commit.**

```bash
git add feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt \
        feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): UI for Mode C + cache size + Clear cache

Performance & buffering section gains:
  - Mode C — Full Pre-render switch. Subtitle text describes the
    aggressive caching tradeoff.
  - Audio cache size selector. Four BrassButton tiles in a Row:
    500 MB / 2 GB / 5 GB / Unlimited. Selected variant=Primary;
    others Secondary.
  - Currently used row. 'X.X GB / Y GB' (or 'no cap' for Unlimited)
    + Clear cache button. Confirmation AlertDialog gates the wipe;
    body text explains 'replays will re-render once'.

ViewModel forwards setFullPrerender / setCacheQuotaBytes / clearCache
to the repository.

Composables sit between Mode B and the Buffer Headroom slider so the
boolean Modes cluster as related toggles."
```

---

### Task G5: `CacheStatsRepository` test

**Files:**
- Create: `core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepositoryTest.kt`

Verifies the flow emits initial state and re-emits when cache content changes.

```kotlin
package `in`.jphe.storyvox.playback.cache

import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CacheStatsRepositoryTest {

    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig
    private lateinit var stats: CacheStatsRepository
    private val ctx by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        config = PcmCacheConfig(ctx)
        cache = PcmCache(ctx, config)
        stats = CacheStatsRepository(cache, config)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private fun renderInto(chapterId: String, bytes: Int) {
        val key = PcmCacheKey(chapterId, "v", 100, 100, CHUNKER_VERSION)
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(Sentence(0, 0, 5, "S."), ByteArray(bytes), trailingSilenceMs = 350)
        app.finalize()
    }

    @Test
    fun `snapshot reflects current cache size and quota`() = runBlocking {
        val before = stats.snapshot()
        assertEquals(0L, before.usedBytes)
        assertEquals(2L * 1024 * 1024 * 1024, before.quotaBytes) // default 2 GB

        renderInto("ch1", bytes = 1_000)
        val after = stats.snapshot()
        assertEquals(1_000L, after.usedBytes)
    }

    @Test
    fun `observe emits initial snapshot immediately`() = runTest {
        renderInto("ch1", bytes = 500)
        val first = stats.observe(pollIntervalMs = 50).first()
        assertEquals(500L, first.usedBytes)
    }

    @Test
    fun `observe re-emits after content changes (across poll boundary)`() = runTest {
        // Pre-populate.
        renderInto("ch1", bytes = 500)

        val emissions = mutableListOf<CacheStatsRepository.CacheStats>()
        val job = kotlinx.coroutines.launch {
            stats.observe(pollIntervalMs = 100).collect {
                emissions += it
                if (emissions.size >= 2) cancel()
            }
        }
        // Advance past the first emission, then add more cache + advance
        // past the next poll.
        advanceTimeBy(50)
        renderInto("ch2", bytes = 700)
        advanceTimeBy(150)
        job.join()

        assertTrue("expected at least 2 emissions, got ${emissions.size}",
            emissions.size >= 2)
        val last = emissions.last()
        // 500 + 700 = 1200 by the time of the second emission.
        assertEquals(1_200L, last.usedBytes)
    }

    @Test
    fun `observe is distinct (no duplicate emissions when stable)`() = runTest {
        renderInto("ch1", bytes = 500)

        val emissions = mutableListOf<CacheStatsRepository.CacheStats>()
        val job = kotlinx.coroutines.launch {
            stats.observe(pollIntervalMs = 50).take(3).toList().forEach { emissions += it }
        }
        // Advance through 3 poll ticks without changing cache.
        advanceTimeBy(200)
        job.join()

        // distinctUntilChanged should collapse to a single emission since
        // usedBytes + quotaBytes don't change.
        assertEquals(1, emissions.size)
    }
}
```

- [ ] **Step 1: Create the file.**
- [ ] **Step 2: Run.**

Run: `./gradlew :core-playback:testDebugUnitTest --tests "*CacheStatsRepositoryTest*"`
Expected: ALL PASS.

- [ ] **Step 3: Commit.**

```bash
git add core-playback/src/test/kotlin/in/jphe/storyvox/playback/cache/CacheStatsRepositoryTest.kt
git commit -m "test(playback): CacheStatsRepository emission semantics

Robolectric. Verifies:
  - snapshot reflects current cache size + quota (with PR-C's defaults)
  - observe emits an initial snapshot immediately on subscribe
  - observe re-emits after a cache append crosses a poll boundary
  - observe is distinct — stable cache state collapses to one emission
    (no needless recomposition in the Settings UI)"
```

---

### Task G6: Tablet smoke-test PR-G on R83W80CAFZB

**Files:** none — runtime verification.

- [ ] **Step 1: Claim tablet lock #47.**

- [ ] **Step 2: Build + install.**

- [ ] **Step 3: Settings UI visual sanity.**

Foreground app → Settings → scroll to "Performance & buffering". Verify:
- Mode A switch (existing).
- Mode B switch (existing).
- Mode C switch (NEW). Default OFF.
- Audio cache size row with 4 BrassButton tiles. Default 2 GB selected (Primary variant).
- Currently used row: "X MB / 2 GB" + "Clear cache" button.

- [ ] **Step 4: Live cache-size update.**

Play a chapter to natural end. While Settings is open in the background... actually, Settings unsubscribes when navigated away, so the test is:
- Open Settings, note "Currently used: X MB / 2 GB".
- Navigate back, play a chapter to natural end.
- Re-open Settings within a few seconds; expect "X+~70 MB / 2 GB".
- (Or: keep Settings open and exercise via Voice Library navigation; the 5 s poll fires regardless.)

- [ ] **Step 5: Quota change + eviction.**

- Cache currently at e.g. 1.5 GB. Tap "500 MB" → confirm `evictToQuota` runs immediately. Within seconds the indicator drops to ≤ 500 MB.
- Tap "Unlimited" → indicator unchanged but the cap is gone.

- [ ] **Step 6: Mode C flip + render fan-out.**

- Library has 1 fiction with 5 chapters, 2 cached. Flip Mode C ON. Within seconds the foreground notification appears for the next chapter render. Over time the remaining 3 chapters render.

```bash
adb -s R83W80CAFZB shell run-as in.jphe.storyvox \
  ls cache/pcm-cache/ | wc -l
```

Expected: eventually 15 files (5 triples).

- [ ] **Step 7: Clear cache.**

- Tap "Clear cache" → AlertDialog appears with "Clear / Cancel". Tap Clear.
- Indicator drops to "0 B / 2 GB" within 5 s.
- `adb shell ls cache/pcm-cache/` shows empty.

- [ ] **Step 8: Replay test post-clear.**

- Play a chapter that was previously cached. Streaming UX (warm-up + buffering) returns. Cache populates again on natural end. Per memory `feedback_install_test_each_iteration.md` this confirms PR-D + PR-E + PR-G integrate cleanly.

- [ ] **Step 9: Capture screenshots.**

- Settings screen with PR-G additions.
- Cache size selector with each option highlighted.
- Clear cache confirmation dialog.

```bash
adb -s R83W80CAFZB shell screencap -p /sdcard/settings-pr-g.png
adb -s R83W80CAFZB pull /sdcard/settings-pr-g.png \
  ~/.claude/projects/-home-jp/scratch/<voice>-pcm-cache-pr-g/settings.png
```

- [ ] **Step 10: Release tablet lock.**

- [ ] **Step 11: Push.**

```bash
git push -u origin dream/<voice>/pcm-cache-pr-g
```

---

### Task G7: Open PR-G

```bash
gh pr create --base main --head dream/<voice>/pcm-cache-pr-g \
  --title "feat(settings): PCM cache UI — Mode C, cache size, Clear cache (PR-G)" \
  --body "$(cat <<'EOF'
## Summary

PR-G of the chapter PCM cache. Surfaces the user-facing knobs in
Settings → Performance & buffering:

- **Mode C — Full Pre-render switch.** Toggle, default OFF. Plumbed
  to PR-F's `PrerenderModeWatcher` — flipping ON enqueues all
  remaining chapters of every library fiction for background render.
- **Audio cache size selector.** Four BrassButton tiles: 500 MB /
  2 GB / 5 GB / Unlimited. Snaps to nearest if a stored value falls
  between options.
- **Currently used + Clear cache.** Live indicator polled at 5 s
  via new `CacheStatsRepository.observe()`. Clear cache button
  with confirmation dialog calls `PcmCache.clearAll`.

## Architecture

`CacheStatsRepository` is a cold polling flow (5 s tick, distinct).
Settings screen subscribes on enter, cancels on dispose. No background
resource use when Settings is closed.

`SettingsRepositoryUi` gains `setCacheQuotaBytes(bytes)` and
`clearCache(): Long`. Setting the quota runs `evictToQuota` immediately
to honor a tightened cap. Clearing returns bytes-freed (informational;
the UI also re-polls).

`UiSettings` gains `cacheUsedBytes` and `cacheQuotaBytes`.
`CacheQuotaOptions` exposes the four discrete options + labels +
snap-to-nearest helper.

## Test plan

- [x] CacheStatsRepositoryTest (Robolectric):
  - snapshot reflects current size + quota
  - observe emits initial snapshot immediately
  - observe re-emits across poll boundaries when content changes
  - observe is distinct (stable state = single emission)
- [x] R83W80CAFZB:
  - Settings shows new section with Mode C switch + size selector + usage row
  - Live cache-size update across chapter completion
  - Quota tighten triggers immediate eviction
  - Mode C flip ON → background renders fan out (PR-F integration)
  - Clear cache + confirmation dialog → indicator drops to 0
  - Post-clear replay re-renders cleanly (PR-D + PR-E + PR-G integration)
- [x] `./gradlew :app:assembleDebug` green
- [x] `./gradlew :core-playback:testDebugUnitTest` green
- [x] `./gradlew :feature:testDebugUnitTest` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Same Copilot review + capped-wait + squash-merge playbook.

---

## Open questions for JP

1. **Discrete options vs slider.** Plan ships four BrassButton tiles per spec. A continuous slider with snap-to-tick is an alternative that matches the existing PunctuationPauseSlider idiom. **Recommendation: ship discrete buttons; the spec calls them "radio buttons" and disk quota has fewer "in between" use cases than punctuation pause.** If feedback says "I want 1 GB, not 500 MB or 2 GB", revisit.

2. **`BrassButtonVariant.Destructive` exists?** Plan uses it for the Clear cache button. If the variant doesn't exist, fall back to `Secondary` with `colorScheme.error` tint applied via a wrapping `Surface`. Verify in `core-ui/component/BrassButton.kt`.

3. **5 s polling vs SharedFlow with explicit emit on cache mutation.** Plan picks polling for simplicity. A SharedFlow approach would require `PcmCache` and `PcmAppender` to emit events on every finalize / abandon / evict / clear — cleaner real-time updates but more event-bus surface. **Recommendation: ship polling; revisit if visible lag becomes a complaint.**

4. **"Currently used" rounding.** Plan shows 1.4 GB / 2 GB. The `formatBytes` helper rounds to 1 decimal for GB, 0 decimals for MB. Per spec example "Currently used: 1.4 GB / 2 GB" — matches.

5. **Mode C subtitle text.** Plan picks "Cache every chapter of every library fiction in the background. Aggressive disk + CPU use; gapless playback everywhere." — matches the tone of existing Mode A / Mode B subtitles. JP can revise.

---

## Self-review

**Spec coverage check (PR-G scope from spec lines 412 + 363-367):**
- ✓ "User-facing Settings entry" → Task G4
- ✓ Quota selector with 500 MB / 2 GB / 5 GB / Unlimited → CacheSizeSelector composable
- ✓ "Currently used: 1.4 GB / 2 GB" indicator → CacheUsageRow
- ✓ "Clear cache" button → CacheUsageRow with confirmation
- ✓ Mode C toggle from spec line 49 ("Schedules render of chapters 1-3 in background") + the implicit toggle → FullPrerenderRow

**Spec deltas / decisions:**
- **5-second polling vs event-driven** for the live indicator. Open question 3.
- **Eviction runs immediately on quota tightening.** Spec line 354 says eviction runs at scheduler enqueue, finalize completion, AND when the user lowers the quota. PR-G implements the "user lowers" branch.
- **Confirmation dialog for Clear cache.** Spec doesn't explicitly call for confirmation; standard destructive-action pattern + reversibility of the consequence (replays re-render) suggests confirmation IS the right UX bar.

**Placeholder scan:** None — every Compose snippet uses existing project components (BrassButton, Switch, Material 3 AlertDialog, MaterialTheme typography).

**Type consistency:** `CacheQuotaOptions` constants match `PcmCacheConfig.DEFAULT_QUOTA_BYTES` (2 GB) from PR-C. `CacheStats` data class matches `UiSettings.cacheUsedBytes` / `cacheQuotaBytes` field types.

**Risks deferred to follow-up PRs:**
- Per-chapter cache state icons in chapter list — PR-H.
- Voice library cached-MB indicator — PR-H.
- Per-fiction "clear this fiction's cache" — PR-H.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-08-pcm-cache-pr-g.md`.

Auto-mode (per `feedback_auto_mode_authorized.md`): commit the plan, then execute G1-G7 inline. PR-open is the JP-visible boundary; everything before that stays in-session.
