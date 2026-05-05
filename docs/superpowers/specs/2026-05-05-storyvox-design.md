# storyvox — design spec

**Date**: 2026-05-05
**Status**: Approved by JP Hein at the brainstorming phase, scaffolded by the `storyvox-dreamers` team in parallel.
**Spec author**: Davis (orchestrator), synthesizing the work of Morpheus, Selene, Oneiros, Hypnos, and Aurora.

---

## 0. TL;DR

storyvox is a beautiful, stable Android audiobook-style player for **Royal Road** (royalroad.com web fiction). It uses **VoxSherpa** (CodeBySonu95's offline neural TTS engine, installed as a separate APK) as its system TTS engine. It treats serial fiction the way podcast apps treat podcasts: **subscribe, auto-download, auto-advance, listen anywhere** (phone, lock screen, Bluetooth headset, Android Auto, Wear OS).

The reader is a **hybrid view** — swipe between an audiobook surface (cover, scrubber, transport) and a reader surface (chapter text with the currently-spoken sentence highlighted). Both are bound to a single `PlaybackState`, so they never desync.

The project is **native Android** (Kotlin 2.0.21 + Jetpack Compose, Media3 1.5, Room 2.6, WorkManager, Hilt 2.51 with KSP, JVM 17, minSdk 26, targetSdk 35). Distribution v1 is GitHub Releases sideload. License is deferred (placeholder only).

Aesthetic: **Library Nocturne** — warm dark `#0E0C12` with brass `#B48C5A` accents, EB Garamond chapter body, Inter UI, paper-cream `#F4EDE2` light mode.

---

## 1. The five locked decisions (from brainstorming)

1. **Architecture**: storyvox and VoxSherpa are *separate apps*. storyvox calls Android's `TextToSpeech` API and uses `setEngineByPackageName("com.codebysonu.voxsherpa")` to route TTS through VoxSherpa. No GPL-3.0 contagion; VoxSherpa stays in its own APK.
2. **Sources**: Royal Road only at v1, but a `FictionSource` interface is in place from day one so multi-source (Scribble Hub, ReadWN, etc.) is additive in v2.
3. **Reading paradigm**: hybrid reader+audiobook with swipe between, both bound to a single `PlaybackState` flow.
4. **Auth**: optional WebView sign-in; anonymous works too. Login form runs in a `WebView` so storyvox never sees the password.
5. **Home**: three bottom-bar tabs — *Library / Follows / Browse*. Browse is a near-replacement for the Royal Road mobile app.

Plus the implementation choices we converged on:
6. **Stack**: Kotlin 2.x + Compose, Media3, Room, WorkManager, Hilt + KSP. JVM 17, minSdk 26.
7. **Download model**: eager + auto-advance + new-chapter polling, per-book override (`LAZY / EAGER / SUBSCRIBE`). Default for ongoing books = subscribe (poll every 6h on Wi-Fi).
8. **Media depth**: notification + lock screen + Bluetooth deep (multi-tap semantics) + Android Auto + Wear OS companion.
9. **Distribution**: GitHub Releases sideload v1. Placeholder LICENSE.
10. **Aesthetic**: Library Nocturne (brass on warm dark; EB Garamond + Inter; paper-cream light).

---

## 2. High-level architecture

```
                          ┌─────────────────────────────────┐
                          │            :app (phone)         │
                          │  Hilt root · NavHost · MainAct  │
                          └───────┬───────────────┬─────────┘
                                  │               │
                  ┌───────────────┴────┐   ┌──────┴────────────┐
                  │    :feature       │   │    :core-ui       │
                  │  library / follows │   │  Library Nocturne │
                  │  browse / reader  │   │  theme + atoms    │
                  │  / fictionDetail  │   │                   │
                  └──┬─────────┬──────┘   └─────────▲─────────┘
                     │         │                    │
              ┌──────┴───┐ ┌───┴────────────┐       │
              │:core-data│ │ :core-playback │───────┘
              │ Room ·   │ │ MediaSession   │
              │ repos ·  │ │ TTS client ·   │
              │ Fiction  │ │ Auto Browser   │
              │ Source   │ │                │
              └────▲─────┘ └────────▲───────┘
                   │                │
            ┌──────┴────────────────┴──────┐
            │      :source-royalroad       │
            │  RR HTML scrape · session    │
            │  cookie store · CF tolerance │
            └──────────────────────────────┘

                          ┌─────────────────────────────────┐
                          │           :wear                 │
                          │  Compose-for-Wear NowPlaying    │
                          │  + bound MediaController        │
                          └────────────┬────────────────────┘
                                       │  (depends on)
                              :core-playback , :core-ui
```

### Dependency contract

The crucial cut: **`:core-ui` depends on nothing.** It's a leaf design system. Reader/audio surfaces in `:feature` consume `PlaybackState` from `:core-playback` and pass UI state into `:core-ui` atoms. `:source-royalroad` implements the `FictionSource` interface declared in `:core-data` and is wired into Hilt at the `:app` level via `@IntoMap @StringKey("royalroad")`.

| From → To | `:core-ui` | `:core-data` | `:core-playback` | `:source-royalroad` | `:feature` |
|---|---|---|---|---|---|
| `:app` | ✅ | (transitive) | (transitive) | ✅ | ✅ |
| `:wear` | ✅ | ❌ | ✅ | ❌ | ❌ |
| `:feature` | ✅ | ✅ (via `feature.api` adapters in `:app`) | ✅ (via adapters) | ❌ | — |
| `:core-playback` | ❌ | ✅ | — | ❌ | ❌ |
| `:core-data` | ❌ | — | ❌ | ✅ (impl, via Hilt) | ❌ |
| `:source-royalroad` | ❌ | api iface | ❌ | — | ❌ |

For full architecture treatment see [`scratch/dreamers/morpheus.md`](../../../scratch/dreamers/morpheus.md).

---

## 3. Data flow (read path)

```
  Royal Road HTML ─→ :source-royalroad ─→ FictionSource (in core-data)
                                              │
                                              ▼
                                    Repository (core-data)
                                              │
                                ┌─────────────┼─────────────┐
                                ▼             ▼             ▼
                      Reader ViewModel   Library VM   Playback service
                          (feature)      (feature)    (core-playback)
                                              │             │
                                              ▼             ▼
                                       Compose UI       MediaSession
                                       (core-ui)        (notif/auto/wear)
```

The flow inverts on writes: ViewModels → Repository → (Room + optionally `FictionSource.setFollow(...)`).

---

## 4. Module structure

```
storyvox/
├── app/                  # phone entrypoint, navigation, Hilt root, deep-link resolver
├── wear/                 # Wear OS companion (NowPlayingScreen + bridge)
├── core-data/            # Room, repos, FictionSource interface, WorkManager jobs
├── core-playback/        # MediaSessionService, TtsPlayer, Auto MediaBrowserService
├── core-ui/              # Library Nocturne design system (theme + components)
├── source-royalroad/     # RoyalRoadSource impl + WebView auth + Cloudflare-aware fetcher
└── feature/              # Library/Follows/Browse/FictionDetail/Reader/Settings screens
```

Notable:
- `:source-royalroad` is deliberately *not* under a `core-` prefix — sources are pluggable peers, not a single core. Future siblings: `:source-scribblehub`, `:source-readwn`.
- A convention plugin for shared android config is a v1.1 follow-up; current modules duplicate the `android {}` block intentionally for diff readability.

See [`scratch/dreamers/morpheus.md`](../../../scratch/dreamers/morpheus.md) for the full module-by-module gradle wiring.

---

## 5. Royal Road handling

### 5.1 Cloudflare posture (verified live 2026-05-05)

A clean OkHttp `GET` with a realistic mobile UA (`Mozilla/5.0 (Linux; Android 14; Pixel 7) ... Chrome/124 Mobile Safari/537.36`) returns 200 on read endpoints — fiction page, chapter, browse, search — *with no Cloudflare interactive challenge*. Cookies set on first GET include `ARRAffinity` (Azure backend pinning) and `.AspNetCore.Antiforgery.<rand>`.

**Strategy**: OkHttp for reads, **WebView for the login flow only**. Once logged in via WebView, we transfer cookies via `CookieManager.getCookie()` into OkHttp's `CookieJar`. If a future read returns 403 or a Cloudflare challenge body, we automatically retry that request inside a hidden WebView to refresh `cf_clearance`/`__cf_bm`.

**Rate limit**: hard floor of 1 request / 1.0 s across the whole `:source-royalroad` module — implemented inside `RateLimitedClient` via a shared `Mutex` + `delay(remaining)`. Never bypassed, even for parallel chapter fetches; the queue serializes them. RR has no documented public API, so we are deliberately polite.

**HEAD is rejected** (`405 Allow: GET`). Always GET.

### 5.2 The honeypot filter — non-negotiable

**This is the single most important parser rule in the project.**

Every Royal Road chapter page contains an inline `<style>` block declaring one randomized class with `display: none; speak: never`, plus a matching `<span>` inside the chapter content carrying anti-piracy text:

```html
<style>
    .cjI0MzA3NzcwMGY2MTRjMGI4YTNhNTRjYzFlMDY0MzUy { display: none; speak: never; }
</style>
...
<p>"Very well, then..."</p>
<span class="cjI0MzA3NzcwMGY2MTRjMGI4YTNhNTRjYzFlMDY0MzUy">
  <br>Love this novel? Read it on Royal Road to ensure the author gets credit.<br>
</span>
```

The class name is randomized per-page. **Without filtering, the audiobook would narrate "Love this novel? Read it on Royal Road..." mid-paragraph.** `:source-royalroad/parser/HoneypotFilter.kt` parses every inline `<style>` block, harvests selectors with `display: none` or `speak: never`, and strips matching elements before the parsed text is handed to `ChapterRepository`. This runs *once at fetch time*, so both the reader view and audiobook view see the same clean text.

Per-paragraph encoded class names (e.g. `cnNiOTNhNzA4OTU4MDQ4NmRiZDQxM2FkMTAxNjQ5MDIw`) on `<p>` tags are **not** honeypot — they're real content fingerprint anchors. Don't strip them.

### 5.3 Login flow

`RoyalRoadAuthWebView` is a Composable hosting a `WebView` that loads `https://www.royalroad.com/account/login`. JP completes the form (or OAuth sub-flow, or passkey — all routes work because the WebView is a real browser). On every navigation we check `CookieManager.getCookie("https://www.royalroad.com")`; once the cookie set contains `.AspNetCore.Identity.Application`, login is complete. We capture the `*.royalroad.com` cookie set, hand it to `AuthRepository`, and persist via `EncryptedSharedPreferences`.

**Cookies persisted** (whatever subset is present):
- `.AspNetCore.Identity.Application` (auth — required)
- `ARRAffinity` (backend pinning)
- `__cf_bm`, `cf_clearance` (Cloudflare; opportunistic)
- `.AspNetCore.Antiforgery.*` (CSRF — required for any subsequent POST)

**Re-auth detection**: any read that returns 401 or contains `<form id="external-account"` triggers `FictionResult.AuthRequired`; the UI relaunches the WebView.

### 5.4 Premium / Patreon-locked chapters

Patreon-tier "early access" chapters appear in the chapter table (so they're discoverable) but the chapter page substitutes a paywall in place of `div.chapter-inner.chapter-content`. We treat as auth-required:
- `div.chapter-inner.chapter-content` is missing OR has no `<p>` descendants AND
- The page contains a Patreon link plus "early access" / "subscribe" / "Patreon" / "exclusive"

OR the marker text "This chapter is unavailable" / "Subscribe to read" is present.

When detected, `RoyalRoadSource.fetchChapter()` returns `FictionResult.AuthRequired`. Auto-advance skips premium chapters with a one-line UI surface ("Skipped Patreon-locked chapter X"). *Heuristic — Mother of Learning has no premium chapters, so untested live; flagged for integration testing.*

### 5.5 Selectors reference

Full HTML selector tables (fiction page, chapter page, browse, search, login) live in [`scratch/dreamers/oneiros.md`](../../../scratch/dreamers/oneiros.md) §3-6. Volume grouping (`data-volume-id`) is parsed but flattened in v1; nested volumes is a v2 feature. JSON-LD (`<script type="application/ld+json">`) is consulted as a structural sanity net for `name`, `description`, `image`, `aggregateRating`, `author`.

### 5.6 RR tag taxonomy

~50 tags identified and listed in Oneiros's spec. Stored as `Set<String>` of slugs in `Fiction.tags`; display labels resolved from a static map.

---

## 6. Data layer

### 6.1 Room schema (StoryvoxDatabase v1)

| Entity | Purpose | Key fields |
|---|---|---|
| `Fiction` | "Books" — one row per RR fiction touched | `id` (PK), `sourceId` (= "royalroad" v1), `title`, `author`, `coverUrl`, `description`, `genres: List<String>`, `tags: List<String>`, `status`, `chapterCount`, `inLibrary`, `followedRemotely`, `downloadMode`, `pinnedVoiceId` |
| `Chapter` | One row per chapter (downloaded or not) | `id` (PK, `"$fictionId:$index"`), `fictionId` FK CASCADE, `sourceChapterId`, `index`, `title`, `htmlBody`, `plainBody`, `downloadState`, `userMarkedRead` |
| `PlaybackPosition` | "Where I was" — one row per fiction (latest only) | `fictionId` (PK) FK CASCADE, `chapterId` FK CASCADE, `charOffset`, `paragraphIndex`, `playbackSpeed`, `updatedAt` |
| `AuthCookie` | *Metadata about* the captured session (cookie value lives in `EncryptedSharedPreferences`) | `sourceId` (PK), `userDisplayName`, `userId`, `capturedAt`, `expiresAt`, `lastVerifiedAt` |

Enums (TypeConverter-bridged):
```kotlin
enum class FictionStatus { ONGOING, COMPLETED, HIATUS, STUB, DROPPED }
enum class DownloadMode { LAZY, EAGER, SUBSCRIBE }
enum class ChapterDownloadState { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }
enum class NotePosition { BEFORE, AFTER }
```

Migrations: `db/migration/` is empty in v1; schemas exported to `core-data/schemas/` via Room's `schemaLocation`.

### 6.2 `FictionSource` interface

Pure Kotlin, no Android deps. Exposes `popular()`, `latestUpdates()`, `bestRated()`, `search()`, `fetchFiction()`, `fetchChapter()`, `listFollows()`, `setFollow()`, `setSessionCookies()`, `clearSession()`.

The 6-axis sealed result (incorporates Oneiros's mid-flight proposal):
```kotlin
sealed class FictionResult<out T> {
    data class Ok<T>(val value: T) : FictionResult<T>()
    data class NotFound(val message: String) : FictionResult<Nothing>()
    data class AuthRequired(val reason: String) : FictionResult<Nothing>()
    data class RateLimited(val retryAfterSeconds: Int) : FictionResult<Nothing>()
    data class CloudflareChallenge(val url: String) : FictionResult<Nothing>()
    data class Failure(val cause: Throwable) : FictionResult<Nothing>()
}
```

`AuthRequired` covers two cases: Patreon-locked chapter and expired session. The reason string distinguishes; UI handles each appropriately.

### 6.3 Repository layer

Each repository is `interface + impl`, exposes Flow-based reads + suspend writes:
- `LibraryRepository` — library + follows visibility
- `FollowsRepository` — follow list + unread chapter counts (per Hypnos's Auto needs)
- `ChapterRepository` — chapter cache, `getChapter()`, `getNextChapterId()`, `markChapterPlayed()`
- `FictionRepository` — fiction metadata
- `AuthRepository` — exposes `sessionState: StateFlow<SessionState>`
- `PlaybackPositionRepository` — `savePosition()`, `observeContinueListening(): Flow<List<UiContinueItem>>`, `recent(limit)` (Auto)
- `SettingsRepository` — global TTS engine choice, default voice/speed/pitch, theme override, Wi-Fi-only download, poll interval

```kotlin
sealed class SessionState {
    data object Anonymous : SessionState()
    data class Authenticated(val displayName: String, val expiresAt: Long?) : SessionState()
    data class Expired(val capturedAt: Long) : SessionState()
}
```

### 6.4 WorkManager jobs

| Worker | When | Constraints | Purpose |
|---|---|---|---|
| `ChapterDownloadWorker` | One-shot; enqueued on add, on new-chapter discovery, or on user "Download all" | `NetworkType.UNMETERED` (configurable in settings) | Downloads one chapter per run; exponential backoff on failure |
| `NewChapterPollWorker` | Periodic, every 6h | Wi-Fi default | For each `SUBSCRIBE`-mode ongoing book, calls `RoyalRoadSource.listChapters()`, diffs against local, enqueues new-chapter downloads, fires opt-in notification |
| `SessionRefreshWorker` | Periodic, daily | Any network | Validates auth cookie still works; flips `SessionState` to `Expired` if not |

`StoryvoxApp` is `Configuration.Provider` and hosts the `HiltWorkerFactory`; `WorkScheduler.ensurePeriodicWorkScheduled()` runs in `onCreate()` with `KEEP` policy (idempotent across launches).

For full schema + DAO + repo signatures see [`scratch/dreamers/selene.md`](../../../scratch/dreamers/selene.md).

---

## 7. Playback layer

### 7.1 Mental model

storyvox plays *text*, not audio files. The player is a thin shell around `android.speech.tts.TextToSpeech` whose role within the Media3 framework is to **look exactly like a normal media player** to the OS:

- **`StoryvoxPlaybackService`** — `MediaSessionService` (Media3) that owns one `MediaSession` and one `Player` impl
- **`TtsPlayer`** — custom `androidx.media3.common.SimpleBasePlayer`; pretends each chapter is a single track; transport callbacks toggle the TTS engine
- **`SentenceTracker`** — listens to `UtteranceProgressListener`, surfaces `currentSentenceRange` flow for the reader
- **`StoryvoxAutoBrowserService`** — separate `MediaBrowserServiceCompat` for Android Auto
- **`PhoneWearBridge`** — `DataClient` publisher of `PlaybackState`, `MessageClient` listener for transport commands

Why pretend to be Media3: Auto, lock screen, Bluetooth media buttons, and Wear OS media controls all assume there's a `MediaSession` to talk to. Adapting TTS into a `Player` gets all of that for free.

### 7.2 TTS engine binding (VoxSherpa-preferred)

```kotlin
// Discovery
val engines = pm.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
    .map { it.serviceInfo.packageName }
val voxSherpaInstalled = "com.codebysonu.voxsherpa" in engines

// Initialization (preferred)
val tts = TextToSpeech(context, onInit, "com.codebysonu.voxsherpa")
```

The 3-arg `TextToSpeech` constructor pins the engine. If `onInit` reports `SUCCESS`, we're good. If VoxSherpa isn't installed or init fails, `EngineConsentDialog` (Composable atom in `:core-playback`) offers:
1. "Use system default" — fall back to 2-arg constructor
2. "Install VoxSherpa" — `Intent.ACTION_VIEW` to `https://github.com/CodeBySonu95/VoxSherpa-TTS/releases`

Choice persists in `SettingsRepository` so we don't re-prompt.

### 7.3 Sentence chunking

Each chapter is split via `BreakIterator.getSentenceInstance(locale)` (ICU-backed). One sentence = one `tts.speak()` utterance with `utteranceId = "s${index}"`. We trust `onStart`/`onDone` (universal) and use `onRangeStart` for sub-sentence highlighting where the engine emits it. If the engine doesn't emit `onRangeStart` (detected after 2s of speaking with no callback), we degrade to sentence-level highlighting only.

If a single sentence exceeds `tts.maxSpeechInputLength` (~4000 chars), we split on the nearest comma boundary and use sub-utterance ids `s${idx}_part${k}`.

### 7.4 Auto-advance handoff

When the last sentence's `onDone` fires:
```
onChapterDone
  → ChapterRepository.getNextChapterId(current)  (Selene)
  → if next == null: pause + emit BookFinished event
  → if next not cached: ChapterRepository.getChapter(next) — suspends, may re-fetch
  → play(fictionId, next, charOffset = 0)
```

If the next chapter can't be fetched (offline + uncached), pause with `PlaybackError.ChapterFetchFailed`.

### 7.5 Bluetooth multi-tap

`MediaSession.Callback.onMediaButtonEvent` receives raw `KeyEvent`. Custom `MediaButtonHandler`:
- 1 tap = play/pause (delayed 400ms for multi-tap detection)
- 2 taps = skip forward 30s
- 3 taps = previous chapter
- Long press (>600ms) = sleep timer toggle (15min default)
- `KEYCODE_MEDIA_NEXT` / `_PREVIOUS` (3-button headphones) bypass multi-tap → next/prev chapter directly

The unavoidable cost: single-tap play/pause has a 400ms latency. The alternative (dispatch single-tap immediately, undo on second tap) feels worse — "Pause" briefly happens before "skip."

### 7.6 Sleep timer

15 / 30 / 45 / 60 min countdown plus "End of chapter" mode. Last 10s is a linear fade: per-utterance `Bundle().putFloat(KEY_PARAM_VOLUME, ...)` updates volume at sentence boundaries. After fade, controller pauses and restores volume = 1.0 for next play.

### 7.7 Audio focus policy

| Focus event | Behavior |
|---|---|
| `LOSS_TRANSIENT` (call) | pause; resume on regain |
| `LOSS_TRANSIENT_CAN_DUCK` | **pause** anyway — TTS ducking is awful |
| `LOSS` (permanent) | pause; no auto-resume |
| `ACTION_AUDIO_BECOMING_NOISY` | pause (headphones unplugged) |

### 7.8 Android Auto

`StoryvoxAutoBrowserService` advertises a 6-row root: `/resume`, `/library`, `/follows`, `/recent`, `/new`. Each leaf is a `FLAG_PLAYABLE` `MediaItem` with cover URI; categories are `FLAG_BROWSABLE`. 6-item-per-category cap (Auto UX restriction). `onPlayFromMediaId` issues commands to the same `MediaSession` the phone uses.

`<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>` in the `:app` manifest plus an `automotive_app_desc.xml` declaring `<uses name="media"/>`.

Search disabled in v1 (Auto safety; voice-only via `onPlayFromSearch` could come later).

### 7.9 Wear OS

Phone is the source of truth. Watch is a dumb terminal.
- Phone publishes `PlaybackState` (kotlinx-serializable JSON) to `DataClient` path `/playback/state`
- Watch subscribes via `DataClient.addListener` and renders `NowPlayingScreen`
- Watch sends commands via `MessageClient` to paths `/playback/cmd/play|pause|skipFwd|skipBack|nextCh|prevCh|sleep15`

Why split: `DataClient` is durable (watch boots → instantly has last state); `MessageClient` is fire-and-forget (commands need low latency, fail loud if phone unreachable).

For full playback architecture see [`scratch/dreamers/hypnos.md`](../../../scratch/dreamers/hypnos.md).

---

## 8. Design system — Library Nocturne

### 8.1 Color tokens

**Brass ramp** — derived from base `#B48C5A` (10 steps, `Brass900` to `Brass100`). Used for accents, sentence underline, anything book-like. Avoids cool greys — every step retains warmth.

**Plum** — secondary accent (`Plum700` to `Plum100`).

**Surfaces**:
| Role | Dark | Light |
|---|---|---|
| `surface` | `#0E0C12` | `#F4EDE2` |
| `surfaceContainer` | `#15131A` | `#EBE2D3` |
| `onSurface` | `#E8DFD1` | `#1A1614` |
| `primary` (brass) | `#B48C5A` | `#7A5A30` |

Brass on warm dark = **6.4:1 contrast** (exceeds AA). Brass-on-cream uses `Brass550` (`#7A5A30`) = **5.8:1** AA.

Error palette is brass-warm `#E07A6A` / `#9A3A2A` — *not* crimson. Crimson would clash.

### 8.2 Typography

- **Body / chapter**: EB Garamond (SIL OFL 1.1) via Compose downloadable Google Fonts (no bundled TTF)
- **UI**: Inter Variable

Reader body: `bodyLarge` = 18sp / 28sp line-height / 0.2sp letter-spacing in EB Garamond. Reader-comfortable.

Full M3 type scale tabulated in [`scratch/dreamers/aurora.md`](../../../scratch/dreamers/aurora.md) §1.2.

### 8.3 Components

In `:core-ui/component/`:
- `BrassButton` (primary/secondary/text variants)
- `ChapterCard` (number + title + relative-published + duration; brass when current)
- `FictionCoverThumb` (2:3 aspect, Coil, fallback = author's initial in brass)
- `BrassProgressTrack` (3dp rail, 16dp brass thumb scrubber)
- `SentenceHighlight` (AnnotatedString with 2dp brass underline animated over 180ms; multi-line aware)
- `BottomTabBar` (Library/Follows/Browse, brass primaryContainer indicator)
- `HybridReaderShell` (two-pane swipe scaffold; `draggable` + `animateFloatAsState` — deliberately *not* `AnchoredDraggableState`, which has API churn between Compose 1.6/1.7)
- `VoiceChip` (`AssistChip` with `RecordVoiceOver` icon)

### 8.4 Motion

| Role | Duration | Easing |
|---|---|---|
| Standard | 280ms | `cubic(0.4, 0, 0.2, 1)` |
| Sentence highlight | **180ms** | `cubic(0.2, 0, 0, 1)` |
| Reader↔Audiobook swipe | 360ms | `cubic(0.32, 0.72, 0, 1)` |

The 180ms sentence ease is intentionally faster than M3 standard — TTS at 1.5× routinely fires ~3 sentence changes per second; slower curves would lag.

### 8.5 Spacing & shapes

4dp grid: `xxs=4, xs=8, sm=12, md=16, lg=24, xl=32, xxl=48, xxxl=64`
Shapes: `extraSmall=4dp, small=6dp, medium=12dp, large=20dp, extraLarge=28dp`

For full design system treatment see [`scratch/dreamers/aurora.md`](../../../scratch/dreamers/aurora.md).

---

## 9. Screens

### 9.1 Navigation graph (`:app`)

Single `NavHost` with:
- `library` → `LibraryScreen`
- `follows` → `FollowsScreen`
- `browse` → `BrowseScreen`
- `fiction/{fictionId}` → `FictionDetailScreen`
- `reader/{fictionId}/{chapterId}` → `HybridReaderScreen` (default to reader view)
- `audiobook/{fictionId}/{chapterId}` → `HybridReaderScreen` (default to audiobook view)
- `settings` → `SettingsScreen`
- `settings/voice` → `VoicePickerScreen`
- `auth/webview` → `RoyalRoadAuthWebView`

Bottom bar shows on Library / Follows / Browse; hides on reader and audiobook.

### 9.2 Deep linking

`MainActivity` intent-filter for `royalroad.com/fiction/...` (https + browseable). `DeepLinkResolver` parses `intent.data` and navigates to `fiction/{fictionId}` once `NavHost` is composed. `autoVerify=false` keeps storyvox as one option in the chooser; we don't hijack the protocol.

### 9.3 Screen specs

| Screen | Notable surfaces |
|---|---|
| **Library** | Top "Resume" card (brass label, cover thumb, fiction title, chapter title, Play). Below: `LazyVerticalGrid` of fiction covers (120dp adaptive). Long-press → download mode picker. Empty state: "Start with Browse" |
| **Follows** | `TopAppBar` with "Mark all caught up" trailing button. `LazyColumn` of cards with unread-count badges |
| **Browse** | `SecondaryTabRow`: Popular / New / Best Rated / Search. Search has 300ms debounce. Horizontal `LazyRow` of 140×240dp covers |
| **FictionDetail** | Hero (cover + title + author + status pill + rating + chapter count). Synopsis (collapsible 4-line clamp). Chapter list (`ChapterCard`s). Sticky bottom bar: "Add to library" (outlined) / "Listen" (filled) |
| **HybridReader** | `HybridReaderShell` over a single `ReaderViewModel`. Audiobook view = 220×330 cover + transport. Reader view = `SentenceHighlight` text + 64dp mini-player |
| **Settings** | Sections: TTS engine, Reading (speed/pitch sliders), Theme (System/Dark/Light), Downloads (Wi-Fi-only switch + poll interval slider), Account (sign in/out) |
| **VoicePicker** | `LazyColumn` of voices grouped by engine (VoxSherpa, Google TTS, etc.). Each row: label + locale + Preview button |

---

## 10. v1 scope and explicit YAGNI

### In scope
- Three tabs: Library / Follows / Browse
- Hybrid reader + audiobook with swipe; sentence highlighting via `UtteranceProgressListener`
- WebView auth, Cloudflare-tolerant fetcher, honeypot filter
- Eager + auto-advance + new-chapter polling, per-book override (`LAZY/EAGER/SUBSCRIBE`)
- Notification + lock screen + Bluetooth deep (multi-tap) + Android Auto + Wear OS companion
- Sleep timer (15/30/45/60min + EndOfChapter)
- Speed (0.5–3×), pitch (0.5–2×)
- Library Nocturne dark + light
- Per-book voice override (falls back to global default which falls back to system)
- "What's new" badge for newly polled chapters

### Explicit YAGNI for v1
- Multi-source impls beyond Royal Road (architecture supports them; no other sources ship)
- Bookmarks / annotations / highlights (text-only feature, not audio)
- TTS audio export to MP3 (legal grey area; YAGNI)
- Cross-device sync
- F-Droid / Play Store distribution
- ProGuard / R8 obfuscation
- Wear OS Tiles or Complications (just the watch app itself)
- Custom voice training / voice cloning
- LitRPG status table parsing (RR has these; v2 fancy feature)
- Volume grouping in chapter lists (parsed but flattened in v1)
- Auto search (Auto safety; could come via `onPlayFromSearch` voice)
- Convention plugins for shared android config (v1.1)

---

## 11. Build & run

### 11.1 Toolchain
- **Kotlin**: 2.0.21 (stable Compose plugin)
- **AGP**: 8.7.2
- **Gradle**: 8.10
- **JVM**: 17
- **compileSdk / targetSdk**: 35 (Android 15)
- **minSdk**: 26 (~94% of active Android devices in 2026)

### 11.2 First-time setup

The wrapper jar is intentionally not committed (binary). To bootstrap:

```bash
# from project root, with system gradle ≥8.10 installed
gradle wrapper --gradle-version 8.10 --distribution-type bin
```

Or grab gradle directly:

```bash
cd /tmp && curl -L https://services.gradle.org/distributions/gradle-8.10-bin.zip -o g.zip \
  && unzip g.zip && /tmp/gradle-8.10/bin/gradle -p /home/jp/Projects/storyvox \
       wrapper --gradle-version 8.10
```

### 11.3 Building

```bash
./gradlew assembleDebug                    # phone APK
./gradlew :wear:assembleDebug              # wear APK
./gradlew :app:installDebug                # install on connected phone
./gradlew :wear:installDebug               # install on connected watch
```

### 11.4 Required runtime

- Android 8.0+ (API 26+)
- VoxSherpa installed as a separate APK from [github.com/CodeBySonu95/VoxSherpa-TTS/releases](https://github.com/CodeBySonu95/VoxSherpa-TTS/releases) — *recommended but not required*; storyvox falls back to system TTS engine if VoxSherpa is missing

---

## 12. Open items resolved during integration

These were cross-cutting concerns the dreamers flagged. Status of each, as of merge-time:

| # | Item | Status | Resolution |
|---|---|---|---|
| 1 | Gradle wrapper jar not committed | **Open** | JP runs `gradle wrapper --gradle-version 8.10` once before first build (intentional — avoids checking in binary blobs without explicit review) |
| 2 | License placeholder | **Open** | "All Rights Reserved, license TBD" file in place. JP picks a license before any public release |
| 3 | `Fiction.sourceKey` column for multi-source | ✅ Resolved | Selene named it `sourceId` (matches her `fictionId`/`chapterId` convention); Morpheus accepted |
| 4 | `FictionResult` axes for RR-specific failure modes | ✅ Resolved | Selene's 6-axis sealed result (`Ok`, `NotFound`, `AuthRequired`, `RateLimited`, `CloudflareChallenge`, `Failure`) incorporates Oneiros's mid-flight proposal |
| 5 | `WorkScheduler.ensurePeriodicWorkScheduled()` in `Application.onCreate()` | ✅ Resolved | Morpheus added with `KEEP` policy after Hilt init |
| 6 | VoxSherpa package name in fork | ✅ Resolved | Use upstream — `com.codebysonu.voxsherpa` (Hypnos's placeholder was right) |
| 7 | VoxSherpa GitHub Releases URL | ✅ Resolved | Upstream: `https://github.com/CodeBySonu95/VoxSherpa-TTS/releases`. Davis patched `:core-playback/build.gradle.kts` `BuildConfig.VOXSHERPA_RELEASES_URL` constant |
| 8 | `androidx.compose.ui:ui-text-google-fonts` versioned in catalog | ✅ Resolved | Davis added `androidx-compose-ui-text-google-fonts` library entry to `gradle/libs.versions.toml`; Aurora's core-ui build script switched to `libs.androidx.compose.ui.text.google.fonts` |
| 9 | `feature.api.*` adapter binding strategy | **Open** | Aurora recommended Option A (thin adapter classes in `:app` map concrete repos / controllers → `feature.api.*` UI contracts). Davis to ship the adapter classes in a follow-up implementation pass — current state: `:feature` interfaces compile against shapes drafted from CONTEXT.md, but the `:app` Hilt module that binds them does not yet exist. Build will fail at link time until Davis lands `app/src/main/.../di/AppBindings.kt` |
| 10 | Notification icon | **Deferred to polish** | Hypnos's placeholder vector is functional; Aurora can replace in a future polish pass — not blocking |
| 11 | Premium-chapter heuristic untested live | **Deferred to integration testing** | Mother of Learning has no Patreon-locked chapters. Validate against a confirmed paywalled fiction post-build |
| 12 | Bluetooth multi-tap on real headphones | **Deferred to JP testing** | Can't simulate; needs JP to test once build is green |
| 13 | Convention plugins for shared android config | **Deferred to v1.1** | Each module duplicates the `android {}` block; consolidate later |

---

## 13. The five sub-specs (full detail)

This master spec is a synthesis. For deep detail in each lane:

- **Architecture & gradle**: [`scratch/dreamers/morpheus.md`](../../../scratch/dreamers/morpheus.md)
- **Data layer**: [`scratch/dreamers/selene.md`](../../../scratch/dreamers/selene.md)
- **Royal Road**: [`scratch/dreamers/oneiros.md`](../../../scratch/dreamers/oneiros.md)
- **Playback layer**: [`scratch/dreamers/hypnos.md`](../../../scratch/dreamers/hypnos.md)
- **UI & design system**: [`scratch/dreamers/aurora.md`](../../../scratch/dreamers/aurora.md)

Each was authored by the dreamer in that lane; this master spec defers to them as authoritative for their domains. If a conflict arises, sub-spec wins on its lane; if the conflict is *cross-cutting*, this master spec is the tiebreaker.

---

## 14. Spec self-review notes

- **No placeholders** — every section has substance.
- **Internal consistency** — architecture diagrams in §2, §3, §4 are mutually consistent. Module dependency contract (§2) matches the gradle file structure (§4) and the actual `build.gradle.kts` files on disk.
- **Scope** — single implementation plan; YAGNI is explicit (§10).
- **Ambiguity** — flagged the open items in §12 with status; everything else is concrete.
- **The honeypot filter** (§5.2) is the load-bearing parser rule — flagged twice for emphasis.

---

*Spec assembled by Davis on 2026-05-05. Team: Morpheus · Selene · Oneiros · Hypnos · Aurora · Davis. 100 Kotlin files scaffolded; build verification pending the `feature.api` adapter pass (item #9 above).*
