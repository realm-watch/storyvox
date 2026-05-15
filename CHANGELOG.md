# Changelog

All notable changes to storyvox land here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
the `versionName` in `app/build.gradle.kts` and the `v*` git tag.

Entries before v0.5.12 are reconstructed from the git log — see
`git log --oneline` for the exhaustive record.

## [Unreleased]

## [0.5.50] — 2026-05-15

### Changed
- **Playing leads the bottom dock** (`a8d8eb0`) — JP final on 2026-05-15. New dock order: `{Playing, Library, Voices, Settings}` (was `{Library, Playing, Voices, Settings}` since v0.5.48). Playing is the most-touched destination during a listening session and now sits in the leftmost cell. The NavHost startDestination is independent of HomeTab's ordinal — cold-launch still lands on Library, the dock just shifts which pill is leftmost.

### Out-of-app (no APK impact, captured here for the trail)
- **Tablet OS gestures restored** — Tab A7 Lite was missing swipe-up-home and long-press-up-recents. Root cause: Samsung Android 14 SystemUI hardcodes its QuickStep binding to `com.sec.android.app.launcher/.globalgesture.TouchInteractionService` (the Samsung One UI Launcher's path). Third-party launchers can register `QUICKSTEP_SERVICE` but Samsung SystemUI silently ignores them. The Samsung One UI Launcher had been uninstalled-for-user but was still on the system partition; restored via `adb shell cmd package install-existing com.sec.android.app.launcher`, set as HOME role, nav-mode toggle rebinds. Captured in `feedback_samsung_launcher_gestures` memory so future debugging skips the launcher-shuffle.
- **TalkBack installed on both devices** — Android Accessibility Suite (`com.google.android.marvin.talkback`) now on R83W80CAFZB + R5CRB0W66MK. The v0.5.41 audit had treated "not installed by default on Samsung tablets" as "cannot install" and substituted uiautomator-XML inspection; this was wrong. Future TalkBack swipe-through audits can run on either device. Captured in `feedback_talkback_installable` memory.

### Awaiting (deferred for separate ship)
- **PR #491** by `adminlip` (first external PR — accessibility-label improvements) sits open. The diff is good but it's bot-authored; JP deferred the merge decision.

## [0.5.49] — 2026-05-15

The five-parallel-agent release. Largest bundle of the session — six features land at once.

### Added — Magical InstantDB sign-in surface (#507, closes #500)
- The v0.5.39 cross-device sync infrastructure finally gets a discoverable Library-Nocturne-coded UI. **First-launch passphrase flow**: brass-edged dialog at NavHost root, gated on `(activeVoice != null && signedIn == null && !dismissed)` so it lands AFTER VoicePickerGate; EB Garamond word-by-word reveal (`candlelight · brass · vellum · starlight`); skip persists permanently.
- **Permanent brass cloud icon** in `LibraryScreen.CenterAlignedTopAppBar` — three states via `SyncStatusViewModel.deriveIndicator()`: `CloudDone` (signed in), `Cloud + MagicSpinner` (syncing), `CloudQueue` (signed out). Tap → ModalBottomSheet with signed-out CTA or signed-in per-domain status grid.
- 15 new tests across `SyncStatusViewModelTest`, `SyncOnboardingViewModelTest`, `PassphraseVisualizerTest`.

### Added — Palace Project library backend (#504, partial close #502)
- **19th fiction backend.** New `:source-palace` Gradle module. OPDS 1.x Atom feed parser walks `<library>.palaceproject.io` catalogs. Free / non-DRM titles route through the existing `:source-epub` pipeline. DRM'd titles surface in catalog with `FictionStatus.STUB` + `AuthRequired("Open the Palace app to borrow …")` reader CTA. `pickOpenAccessEpub()` is the load-bearing DRM boundary — strictly excludes LCP license MIMEs. UrlMatcher claims `<library>.palaceproject.io/...` URLs through the magic-link cascade.
- Libby + Hoopla deferred with detailed scope docs in `scratch/libby-hoopla-palace-scope/` — Libby v1 will be audiobook-only (MP3 borrows via `:source-radio`), Hoopla v1 reverse-engineered HLS streaming.

### Added — Voice bundles in Plugin Manager (#505, closes #501)
- **Plugin Manager grows a fully-iterated Voice bundles section**. Settings → Plugins now lists every installed voice family as a brass-edged card alongside Fiction sources and Audio streams: Piper, Kokoro (~330 MB, 53 speakers), KittenTTS (~24 MB, 8 en_US speakers), Azure HD voices (BYOK cloud, surfaces "Configure →" CTA when no key set), and VoxSherpa upstreams placeholder. Same shape as Fiction-source cards: brass voice-glyph monogram, On/Off toggle, Local/Cloud/BYOK + live voice-count chips, tap-for-details modal, "Manage voices →" deep-link.
- New **`VoiceFamilyRegistry`** singleton in `:core-playback` with declarative metadata. Adding a new family is one descriptor entry + `EngineType.X` case.
- **Voice Library filter**: when a family is OFF the picker hides its voices across favourites, installed, and available buckets.

### Added — PCM cache PR F (#503, partial close #86)
- **WorkManager background pre-render of N+1 / N+2 chapters** while N plays. `PcmRenderScheduler` + `ChapterRenderJob @HiltWorker` with `setForeground` + `DATA_SYNC`. Holds `EngineMutex` per-sentence; handles Piper / Kokoro / Kitten; skips Azure (BYOK + per-character billing). `PlaybackModeConfig.fullPrerender` flow + `PrerenderTriggers` + `FictionLibraryListener` seam.
- `EngineMutex @Singleton` hoist (#11 race protection) — foreground playback and background render serialize on the same mutex.
- R8 keep rule for `ChapterRenderJob` (WorkManager's `WorkerFactory` looks up FQN reflectively).

### Added — PCM cache PR G — Settings UI (#508, partial close #86)
- **Settings → Performance gains three rows**: Full Pre-render switch (Mode C, default OFF), 4-tile BrassButton quota picker (500 MB / 2 GB / 5 GB / Unlimited), live "Currently used: X / Y" indicator + Clear-cache button with confirmation.
- `pref_full_prerender_v1` on the InstantDB sync allowlist (the per-fiction listening preference). Cache quota stays device-local because storage capacity varies between phone and tablet.

### Added — PCM cache PR H — status icons (#506, closes #86 series)
- **Brass `ChapterCacheBadge` icons in Fiction Detail chapter rows**: `Icons.Outlined.HourglassTop` (pre-rendering, alpha-pulse) and `Icons.Filled.Bolt` (complete). Per-voice "X MB cached" labels in Voice Library. "Clear fiction cache…" overflow-menu action with confirmation dialog.
- New `CacheStateInspector` (`:core-playback`, `@Singleton`) wraps `PcmCache.isComplete` / `metaFileFor` / `rootDirectory`. StateFlow plumbing from `FictionDetailViewModel.combine` lands `ChapterCacheState` on each `UiChapter`.

### The 8-PR PCM cache series is complete
| PR | Lands | What |
|---|---|---|
| A+B+C | #100 (2026-05-08) | Filesystem layer |
| D | v0.5.47 | Streaming-tee writer (cache accumulates) |
| E | v0.5.48 | Cache-hit playback (instant replays) |
| **F+G+H** | **v0.5.49 (this release)** | **Background pre-render + Settings UI + status icons** |

### Fixed
- **NavStructureTest pinned to v0.5.48's 4-tab dock** (`5c037d0`) — the test still asserted v0.5.40's 2-tab contract, failing on every build since v0.5.48. Now pins `[Library, Playing, Voices, Settings]`.

### Under the hood
- **5 parallel agents** in isolated worktrees + 1 hotfix on main shipped this release in ~3 hours of orchestrator wall-clock. The one cross-contamination incident (PR-G agent worked on main's repo by accident via bare `cd`) was caught + recovered without dropping work.
- 18 → 19 fiction backends. `:source-palace` joins the registry.
- `VoiceFamilyRegistry` (`:core-playback`) is the seed for future out-of-tree voice engines.
- `pref_full_prerender_v1`, `pref_sync_onboarding_dismissed` on the InstantDB sync allowlist.

## [0.5.48] — 2026-05-15

### Fixed
- **Bottom nav bar disappeared on Library / home surfaces** (`2efabe6`) — PR #475 (magic-link, v0.5.40) re-registered the LIBRARY route as `library?sharedUrl={sharedUrl}` to accept a system `ACTION_SEND` intent. But the `HOME_ROUTES` set still used the bare LIBRARY constant (`"library"`), and `currentBackStackEntryAsState().destination.route` returns the *full pattern* including the query template, so `route in HOME_ROUTES` always returned false on the Library home surface. Net: bottom nav vanished from the Library tab (by far the most-visited surface) — user could only switch to Settings via the gear icon or a deep-link. Reported by JP on both tablet + Flip3 v0.5.47. Fix strips the `?` suffix in `isHome()` so the substring before the nav-arg template matches the LIBRARY constant; verified on tablet post-fix with the Library + Settings cells visible at y=1239-1300, matching the v0.5.42-v0.5.46 layout exactly.

### Added — PCM cache PR E
- **Cache-hit playback via `CacheFileSource`** (#498, PR E of the PCM cache series, partial close [#86](https://github.com/techempower-org/storyvox/issues/86)). The user-perceptible win. When `PcmCache.isComplete(key)` returns true, `EnginePlayer.startPlaybackPipeline` opens a memory-mapped `CacheFileSource` (with RAF fallback for corrupt-cache `IOException`) instead of starting `EngineStreamingSource` + synthesis. Cache-hit replays start audio in <100 ms instead of the 2-5 s synthesis warm-up. Truncated PCM or corrupt manifest falls through to streaming with a `pcm-cache hit-open FAILED` logcat event. Partial-cache wipe-and-restart from PR D handles the in-progress edge.
- **15 new Robolectric contract tests** in `CacheFileSourceTest`: byte-equality on sequential read, trailing-silence propagation, seek edge cases (before-first, mid-range, past-last), truncated-pcm `IOException` fallthrough, `bufferHeadroomMs = Long.MAX_VALUE`, `producerQueueDepth/Capacity = 0`, close-releases-fd, `startSentenceIndex` resume + past-end, `finalizeCache` no-op + idempotency, sample-rate propagation, `SentenceRange` round-trip.
- **`PcmSource` interface gained `bufferHeadroomMs` + `finalizeCache()`** with safe defaults. Sealed interface, same module, no external impls so no break.
- **New logcat events** (tag `EnginePlayer`): `pcm-cache HIT chapter=… voice=… speed=… pitch=… fromSentence=… base=<sha12>` and the matching `MISS`. Primary verification surface now that `adb run-as` is gone post-v0.5.46's `isDebuggable=false`.

### Cache-rollup arc
- **PR D** (v0.5.47): cache files start accumulating on disk as you listen.
- **PR E** (this release): tap a chapter you've already heard → instant audio.
- **PR F** (open at #499, lands as v0.5.49): WorkManager background-render N+1 / N+2 chapters while N plays — so even *first-time* playback of the next chapter skips synthesis.
- **PR G**: Settings UI for cache quota + Mode C (full-fiction pre-render).
- **PR H**: status icons.

## [0.5.47] — 2026-05-15

### Added — PCM cache PR D
- **Streaming-tee writer wires PcmAppender into EngineStreamingSource** (#497, PR D of the PCM cache series, partial close [#86](https://github.com/techempower-org/storyvox/issues/86)). Synthesized PCM now gets tee'd to disk as it streams to the audio sink, ahead of the cache-hit-replay landing in PR E. Per-sentence tee in the serial producer path; tee-from-the-sequencer (not workers) in the parallel path so byte offsets stay monotonic even when workers complete out of order. New `EngineStreamingSource` ctor param `cacheAppender: PcmAppender?` (`@Volatile`-shadowed for null-on-abandon), `finalizeCache()`, and `cacheTeeErrors: StateFlow<Int>`. `close()` + `seekToCharOffset()` abandon + null the appender. `EnginePlayer` injects `PcmCache`, constructs `PcmCacheKey` from `(chapterId, voiceId, speed, pitch, CHUNKER_VERSION, pronunciationDictHash)`, wipes stale partials per the abandon-and-restart policy, finalizes + LRU-evicts on natural chapter-end.
- **5 new contract tests** in `EngineStreamingSourceCacheTeeTest` (Robolectric): per-sentence tee, finalize → `isComplete = true`, close abandons, seek abandons, null-appender back-compat no-op, finalize idempotency.

### What user sees vs what's queued
- **This release**: cache files start accumulating on disk as you listen. Zero user-perceptible change today.
- **PR E (next)**: cache-hit playback — revisiting a chapter swaps in `CacheFileSource`, skipping synthesis entirely. That's the user-facing "instant replay" win.
- **PR F**: WorkManager / `RenderScheduler` background renders for next-chapter pre-render.
- **PR G**: Settings UI for cache quota + Mode C.
- **PR H**: status icons.

### Build-config caveat from v0.5.46 carries forward
- `adb shell run-as in.jphe.storyvox ls cache-dir` no longer works because `isDebuggable=false` blocks `run-as`. On-device cache verification will use logcat events + the in-app diagnostic surface that PR G adds. PR D's tablet smoke-test deferred to PR E (cache-hit playback is the observable signal anyway).

### Under the hood
- One spec delta from the PR D plan file: `PcmCacheKey` (shipped in PR C / #100) already carries `pronunciationDictHash` as a 6th field beyond the plan's 5-field listing. EnginePlayer snapshots `cachedPronunciationDict.contentHash` into the key so dict edits self-evict cached chapters.
- Gradle JVM heap bumped 4G → 8G (commit `40f7b9c`) — v0.5.46's tag CI hit SIGTERM during R8 + ART profile expansion; 8G prevents that.

## [0.5.46] — 2026-05-14

### Performance — the actual cold-launch win lands

- **`isDebuggable = false` on the shipped build** (#409 part 4 — JP design call resolved 2026-05-14). Activates ProfileInstaller's AOT compilation of the bundled Baseline Profile from v0.5.45. Combined with R8 (also v0.5.45), this is what makes the **~4.5 second tablet cold-launch win** actually materialize — the "Skipped 219 frames" first-composition pass is gone because the hot paths are now AOT-compiled at install time. The lost Android Studio debugger-attach capability is not in use (storyvox dev happens through agents + `./gradlew installDebug` + logcat); if a future workflow needs Studio attach, introduce a separate `localDev` build type rather than flipping this back.
- **Lint-vital escalation disabled on assemble** (#409 part 4 secondary fix) — non-debuggable build types trigger `lintVitalAnalyzeDebug` as part of `:app:assembleDebug`, and AGP's lint hit an internal bug on `:core-ui`'s `A11yLocals.kt` ("Unexpected failure during lint analysis"). The file compiles + unit-tests fine; it's a lint-internal crash. Workaround: `lint { checkReleaseBuilds = false }` skips the vital escalation while `./gradlew :app:lintDebug` still runs normally.

### Cold-launch arc summary (v0.5.42 → v0.5.46)

| Release | Change | Tab A7 Lite cold launch |
|---|---|---|
| v0.5.42 | Baseline | ~6.7 s |
| v0.5.43 | Phase 2 a11y (no perf change) | ~6.7 s |
| v0.5.44 | Three deferred-init fixes | ~6.5 s (-185 ms) |
| v0.5.45 | + R8 minification (DEX -73%) + Baseline Profile bundled (dormant) | ~6.5 s |
| **v0.5.46** | **+ `isDebuggable=false` activates BP AOT compilation** | **target ~2 s (actual measure post-install)** |

Real numbers land in the Slack post once installed.

## [0.5.45] — 2026-05-14

### Performance — Baseline Profile + R8 minification

- **R8 minification ON for the shipped build** (#496, partial close [#409](https://github.com/techempower-org/storyvox/issues/409)). `isMinifyEnabled = true` on the `debug` build type (which IS the shipped artifact today). **DEX bytecode shrinks 87.0 MB → 23.3 MB (-73.3%) uncompressed**; the 30+ pre-R8 dex files collapse to 2. APK on-disk only drops ~1.6 KB because ~95 MB of the APK is native `.so` files (libonnxruntime + libsherpa-onnx across 4 ABIs) that R8 can't touch. APK uncompressed total: 199.9 MB → 136.2 MB (-31.9%). Comprehensive `app/proguard-rules.pro` keeps every reflection surface load-bearing: the KSP-generated `in.jphe.storyvox.plugin.generated.**` package (without this, all 18 source plugins silently drop), `kotlinx.serialization` umbrella, Hilt + Room belt-and-suspenders, Jsoup/OkHttp `-dontwarn`. Manual tablet verification: cold launch + all 18 chips + Notion/HN/Royal Road/Telegram fiction detail open + zero FATAL/SerializationException entries in logcat.

- **Baseline Profile generated + bundled** (#495, partial close #409). New `:baselineprofile` Gradle module + `BaselineProfileGenerator` instrumented test that walks the cold-launch hot path: LAUNCHER → Library (default landing) → Browse (Notion chip default-selected post-`9370b39`) → Settings hub → first fiction → Reader. 3 iterations, captured into `app/src/main/generated/baselineProfiles/baseline-prof.txt` (3265 storyvox-specific entries). Refresh manually with `./gradlew :app:generateBaselineProfile` (~5 min on the tablet); NOT on the critical-path CI build. **Measured -33.6% (1965 ms → 1304 ms) cold launch on the `nonMinifiedRelease` variant** during macro-benchmark capture.

### ⚠️ The BP cold-launch win is dormant on the shipped APK

ProfileInstaller only AOT-compiles bundled profiles on **non-debuggable** APKs. Today the shipped `debug` build type still has `isDebuggable = true` (default), so the profile bundles but doesn't activate at install time. The 4.5-second latent win (Skipped 219 frames on first composition) stays unrealized until **one of**:

- (a) `isDebuggable = false` flips on the `debug` build type (one-line change; trades local Studio debugger attach for the cold-launch win), **OR**
- (b) storyvox grows a real release flavor (#16, queued v1.0 prerequisite) and CI ships that.

This is a JP design call. The infrastructure (BP profile + R8 rules + benchmark build type) is fully wired ahead of either path.

### Under the hood
- New `benchmark` build type — non-debuggable, no R8, debug-signed. Used by the BaselineProfile producer's `nonMinifiedRelease` variant to measure honest "with profile" / "without profile" deltas. Not shipped.
- `release` build type now has R8 on + the same single-keystore reuse (forward-looking placeholder for #16).
- Required `androidx.benchmark:macro` 1.4.1 — 1.3.4 chokes on Android 14's new `pm dump-profiles` stdout prefix on the Tab A7 Lite.
- Most-likely-to-bite-future-devs: the `in.jphe.storyvox.plugin.generated.**` proguard keep is load-bearing. Drop it and Browse goes blank with no compile error. Documented at the top of `app/proguard-rules.pro`.

## [0.5.44] — 2026-05-14

### Performance — partial close of #409

- **Three deferred-init fixes shave ~185 ms off Tab A7 Lite cold launch** (#494, partial close [#409](https://github.com/techempower-org/storyvox/issues/409)). Tablet 6714 ms → 6529 ms (-2.8%), Z Flip3 1342 ms → 1201 ms (-10.5%) measured via Macrobenchmark at `StartupMode.COLD`, median of 10 / 5 iterations.
- **VoxSherpa engine-bridge seeds deferred** (~100 ms tablet) — `applyPitchQualityFromSettings` + `applyPerVoiceEngineKnobsFromSettings` moved from `StoryvoxApp.onCreate` to a `Choreographer.postFrameCallback` chain in `MainActivity`. Verified: `libsherpa-onnx-jni.so` now `dlopen`s ~250 ms *after* the "Displayed" event instead of before. The engine's `Sonic` instance still doesn't construct until the first audio-buffer request, so seed always completes before any in-flight render — no behaviour change.
- **AccessibilityStateBridge eager-stateIn'd** (~30 ms tablet) — replaced the v0.5.43 cold `callbackFlow` exposure with a hot `StateFlow` built via `stateIn(scope = IO, started = Eagerly, initialValue = AccessibilityState())`. Activity-injected bridge wrapped in `Lazy<>`. Drops the `getEnabledAccessibilityServiceList` binder hop off the Compose Main-dispatcher first-composition path. Mid-session TalkBack changes still fire correctly.
- **Data-layer warm-up** (negligible measured, kept anyway) — `warmDataLayer()` in `StoryvoxApp.onCreate` kicks off `Lazy<StoryvoxDatabase>.get()` + Fiction/Shelf/PlaybackPosition repos on IO. Race-loss-safe because it shares Hilt's `DoubleCheck` cache with the foreground path. Foundation for the next bigger lift.

### Deferred (the real wins are next)
- **Baseline Profile generation** (estimated ~1.5–2.5 s tablet win) — agent in flight, lands as v0.5.45. The 4.7-second "Skipped 219 frames" first-composition pass on this CPU is Compose + Hilt graph construction on the debug-flavor (non-AOT) classpath; AOT-compiling the hot paths at install time is the surgical fix.
- **R8 minification** (estimated ~15–25% win) — currently `isMinifyEnabled = false`, queued for JP design call (reflection-heavy modules need a proguard-rules audit before flipping it on).

## [0.5.43] — 2026-05-14

### Added — Accessibility Phase 2: everything from the audit, wired

- **High-contrast theme variant** (#493, closes #486 part 1) — `brass-on-near-black` Library Nocturne. Backgrounds `#1a1410` → `#000000`; brass `#b88746` → `#ffc14a`. Hits WCAG **AAA** (~14:1+ body text) without abandoning the brand. New `LibraryNocturneHighContrastTheme` toggled by `pref_a11y_high_contrast` OR `accessibilityState.isTalkBackActive` (the bridge auto-detects). Light variant: pure-white inverse with darker brass for AAA on white.
- **Reduced-motion fold-in** (#493, closes #486 part 2 + #480) — new `LocalReduceMotion` CompositionLocal in `:core-ui`, set from `pref_a11y_reduced_motion || accessibilityState.isReduceMotionRequested` (the bridge tracks `Settings.Global.ANIMATOR_DURATION_SCALE`). 8 animation sites fold the flag in: `AnimatedVisibility` snap-rather-than-fade, `tween()` → `snap()`, `animateFloatAsState` / `animateDpAsState` → `spring(stiffness=High)`, `Crossfade` skip. Per JP design call: auto-on when system "Remove animations" is on; user override in the subscreen always wins.
- **Touch-target widener** (#493, closes #479 + #486 part 3) — new `Modifier.accessibleSize()` helper in `:core-ui/a11y/` that returns 48dp baseline, 64dp when `LocalAccessibleTouchTargets.current` is true (set from `pref_a11y_larger_touch_targets || accessibilityState.isSwitchAccessActive`). Applied at the 3 auth-WebView buttons + the VoiceLibrary favorite-star (previously 36dp → below WCAG 2.5.5 minimum).
- **TalkBack inter-sentence pacing** (#493, closes #486 part 4) — `EngineStreamingSource` adds `pref_a11y_screen_reader_pause_ms` ms of silence between sentences (slider 0–1500ms, default 500ms) **only when** `accessibilityState.isTalkBackActive`. Speed-scaled so 2× playback doubles the effective pause budget. 3 producer paths touched.
- **Chapter-header readout branching** (#493, closes #486 part 5) — `ChapterCard` content-description respects `pref_a11y_speak_chapter_mode` (`Both` / `NumbersOnly` / `TitlesOnly`). New `LocalA11ySpeakChapterMode` CompositionLocal.
- **Font-scale typography pipeline** (#493, closes #486 part 6) — `scaledTypography` multiplies `LibraryNocturneTypography` font sizes by `pref_a11y_font_scale_override` (0.85–1.5×, default 1.0) on top of system font scale. Applied via `MaterialTheme(typography = scaledTypography)`.
- **Reading-direction at NavHost root** (#493, closes #486 part 7) — when `pref_a11y_reading_direction != FollowSystem`, the entire `StoryvoxNavHost` is wrapped in `CompositionLocalProvider(LocalLayoutDirection provides Ltr|Rtl)`. Escape hatch for RTL testing.
- **TalkBack-install nudge** (#493, closes #488) — when the user touches a screen-reader-related a11y row but TalkBack isn't running, a dismissible brass-edged info card appears: "TalkBack isn't running — these settings activate when you turn on TalkBack in Android Settings → Accessibility." Persists dismissal via the new `pref_a11y_talkback_nudge_dismissed` flag (on the InstantDB sync allowlist).
- **`docs/accessibility.md`** (#492, closes #487) — canonical accessibility reference at `storyvox.techempower.org/accessibility` covering: audit method, Phase 1+2 scope, design calls, partnership-outreach plan. Settings → Accessibility "Learn more" row opens it via `LocalUriHandler`.

### Fixed — Accessibility Phase 2: static labels + Roles + content descriptions

- **Switch primitive labeled** (#492, closes #478) — `SettingsSwitchRow` refactored to `Modifier.toggleable(role = Role.Switch)`. Covers 4 of 7 unlabeled-Switch sites at once; the other 3 (`SettingsScreen.kt:1204`, `PronunciationDictScreen.kt`, `ManageShelvesSheet.kt`, `DebugScreen.kt`, `VoiceQuickSheet`) given the same shape. `PluginManagerScreen.kt:299` gets `contentDescription` on the Switch instead because the surrounding card has its own tap.
- **BottomTabBar Role.Tab + selected** (#492, closes #485) — each `TabCell` now declares `Modifier.semantics { selected = isSelected }` + `Modifier.clickable(role = Role.Tab, onClickLabel = tab.label)`. New `BottomTabBarSemanticsTest` pins the contract.
- **Progress indicator semantics** (#492, closes #484) — `SyncAuthScreen.InProgress` + `FictionDetailScreen`'s epub-export spinner get `contentDescription = "Loading: <label>"` + `liveRegion = Polite`.
- **27 bare clickable callsites swept** (#492, closes #481) — `Role.Button` / `Role.Checkbox` / `Role.Switch` + `onClickLabel` added across 13 files. `VoicePickerGate`'s intentionally Role-less tap-swallow annotated.
- **Hard-coded fontSize cleanup** (#492, closes #483) — `MilestoneDialog` (12 sites) + `GitHubSignInScreen` (1) rebound to typography ramp tokens (`titleMedium`/`bodyMedium`/`titleLarge`/`bodyLarge`/`displaySmall`). `DebugOverlay` + `DebugScreen` get kdoc notes explaining intentional bypass for the px-pinned debug HUD.
- **Default-theme contrast tweaks** (#493, closes #477) — `plum500`, `errorContainer` (dark), and both `outlineVariant` lifted to clear **WCAG AA-normal** (4.5:1+) within the existing Library Nocturne aesthetic. AAA on body text contract-tested across every surface tier.
- **Icon contentDescription verify-sweep** (#492, closes #482) — 36 `cd=null` + 28 `IconButton:MISSING` callsites audited; all confirmed correctly decorative or labeled via inner-Icon semantic merge. Documented in `docs/accessibility.md`.

### Under the hood
- 8 new contract tests: `HighContrastThemeTest`, `TypographyScaleTest`, `AccessibleTouchTargetTest`, `ReduceMotionTest`, `EnginePlayerTalkBackPacingTest`, `ReadingDirectionMappingTest`, `SettingsSwitchRowToggleableTest`, `BottomTabBarSemanticsTest`.
- New `LocalReduceMotion`, `LocalAccessibleTouchTargets`, `LocalA11ySpeakChapterMode`, `LocalIsTalkBackActive` CompositionLocals in `:core-ui/a11y/`. The state bridge from #489 stays the single source of truth; CompositionLocals are read-side fan-out.
- 7 prefs from #489 are now load-bearing — every consumer reads the `pref || detected` fold.
- 12 of 12 a11y audit findings (#477–#488) now closed.

## [0.5.42] — 2026-05-14

### Added
- **Telegram backend** (#490, closes #462) — public-channel reader via Bot API. New `:source-telegram` Gradle module + Settings card (mirrors Discord). 17 → 18 fiction backends. Architectural caveat documented in PR body: Telegram Bot API gives bots no access to message history that predates their invitation to a channel — v1 accumulates posts in-memory via `getUpdates` polling on each Browse refresh; user sees zero chapters until the channel admin posts something new post-invite. Bot token stays device-local (no InstantDB sync until follow-up coordination with the `:core-sync` allowlist).
- **Accessibility Settings scaffold** (#489, Phase 1 of the #486 epic) — new `Settings → Accessibility` subscreen with 7 functional rows (high-contrast toggle, reduced-motion toggle, larger-touch-targets toggle, screen-reader-pause slider 0–1500 ms, font-scale slider 0.85–1.5×, speak-chapter-mode selector, reading-direction selector) plus an "About these settings" info card. All wire through `SettingsViewModel` to 7 new `pref_a11y_*` DataStore keys on the InstantDB sync allowlist. **New `AccessibilityStateBridge`** in `:feature` (real impl in `:app`) exposes `(isTalkBackActive, isSwitchAccessActive, isReduceMotionRequested)` as a hot `callbackFlow` from `AccessibilityManager` + `Settings.Global.ANIMATOR_DURATION_SCALE`. **Phase 1 ships toggles + state-bridge only** — Phase 2 wires the behaviors (high-contrast theme swap, reduced-motion fold-in for `AnimatedVisibility`/`tween`, 48dp→64dp `clickable` widener, TalkBack inter-sentence pacing, chapter-header readout branching, font-scale typography pipeline, `LayoutDirection` at NavHost root). TODO/Phase-2 comments at every consumer site.

### Fixed
- **Bottom tab bar no longer blocks the Android home swipe** (`46262b0`) — `BottomTabBar.kt` called `Modifier.systemGestureExclusion()` on each tab cell to claim the ~64 dp `mandatorySystemGestures` rect for taps. That worked for tap reliability but blocked the OS swipe-up-home and long-press-up-recents gestures entirely — they hit our exclusion rect and never reached the system. Fix drops the exclusion entirely; `Modifier.windowInsetsPadding(WindowInsets.navigationBars)` already lifts cells above the visible gesture pill, which is enough for tap reliability in practice.
- **Notion pinned to first chip** (`9370b39`) — per JP design call, TechEmpower's Notion content is the default Browse landing surface when no user-Notion token is configured. `SourcePluginRegistry` sort now puts Notion at position 0 before the existing category-then-alphabetical fallback, which also makes it the default-selected chip on Browse open (`BrowseViewModel` picks `descriptors.firstOrNull { it.defaultEnabled }`).

### Closed (stale)
- **#447** Notion prefilled DB id on fresh install — closed citing #474 (the v0.5.40 design-call fallback already covers this).
- **#442** Gutenberg chapter playback hung at 0:00 — closed citing #467 (the v0.5.38 `stripTags` + zero-sentences guard already fixed this).
- **#440** Settings gear icon dumped users into Voice & Playback — closed citing #467 + the v0.5.39 SettingsHubScreen.
- **#438** Library had two adjacent tab rows with overlapping labels — closed citing #467 + the v0.5.39 nav restructure (#469).

### A11y audit findings filed (Phase 2 targets, not fixed in this release)
- **12 new accessibility issues filed (#477–#488)** by the a11y-audit agent. Body-text contrast is **AAA** (12-15:1) across both themes; failures concentrate in *secondary* colors (plum 3.31:1, error-container 4.00:1, brass-on-light-highest 4.33:1, outlineVariant 1.58-1.60:1 in both themes). 9 unlabeled clickables all trace to a shared `SettingsComposables.kt` Switch primitive — `~10` LOC fix kills 4 of 7 sites at once (#478). 0 RTL violations, 0 empty `contentDescription` strings, AudiobookView transport controls are exemplary. Phase 2 targets are #478 → #485 → #486 (the subscreen epic, now unblocked by #489).

## [0.5.41] — 2026-05-14

### Added
- **Craigslist regional feeds template** (#476, closes #464) — local-marketplace listings as audiobook chapters. Inside `:source-rss`, a curated catalogue of **51 regions** (top-50 US metros + Nevada City) × **7 categories** (all-for-sale, free stuff, cars+trucks, furniture, electronics, apartments, jobs) drives a new collapsible "Craigslist regional feed" template card in the Browse → RSS add sheet. Pick a region chip + a category chip, see the resolved feed URL live, tap Subscribe — the feed enrolls via the existing RSS pipeline (DataStore persistence, polling, FictionDetail rendering). Each region+category becomes a fiction; each listing becomes a chapter. JP's "uninstalled Facebook, would love to browse the local marketplace and have listings read to me" use case, shipped without scraping and without a new Gradle module.
- **Magic-link claims Craigslist URLs** — `RssSource.matchUrl` recognizes `<region>.craigslist.org/*` for any of the 51 curated regions, returns `RouteMatch(confidence = 0.7f, label = "Craigslist (<region>)")`. Paste a CL URL from a browser share → routes through `RssSource` → opens the Add-by-URL sheet pre-resolved.

### Fixed
- **RSS parser now handles RDF / RSS 1.0** (#476) — Craigslist serves `<rdf:RDF>` feeds (not `<rss>`), which the previous parser rejected silently. New parser branch handles the RDF root; pinned with a recorded Craigslist fixture in `:source-rss/src/test/resources/`. Unblocks any other publisher still on RSS 1.0.

### Under the hood
- `CraigslistTemplates.kt` is the single canonical region+category catalogue. `composeFeedUrl(region, category)` formats the URL; 15 parametric tests cover the matrix.
- `BrowseCraigslistTemplateCard` is a self-contained Compose composable inside `BrowseRssManageSheet` — no new screen, no new top-level Browse chip, no new Hilt graph dependencies.
- 28 new tests: 15 catalogue + 5 RDF fixture parse + 8 matcher. Pure-JVM (no Robolectric needed).

### Deferred (per #464 v1.5+ scope)
- Distance / price filters (CL exposes these as query params; just need UI).
- Saved-search composer for free-form CL queries.
- Auto-normalize pasted CL URLs to `?format=rss`.
- Region pinning (favorite a region for one-tap subscribe).
- Dedupe-miss probe: "Already subscribed" check is on lowercased URL; trailing slash + reordered query args could slip through. Worth a manual probe on tablet next release if it surfaces.

## [0.5.40] — 2026-05-14

### Added
- **Magic-link audiobook** (#475, closes #472) — paste any URL into the Add-by-URL sheet and storyvox routes to the best backend, with a Readability catch-all so **no URL is a dead-end**. New `UrlMatcher` capability interface (separate from `FictionSource` so it stays opt-in); 15 backends implement it (Hacker News, Notion, Outline, Discord, Radio, RSS, EPUB direct-link, Memory Palace, AO3, Gutenberg, Standard Ebooks, PLOS, Wikipedia, Wikisource, arXiv). Royal Road + GitHub keep their existing `UrlRouter` regex bank for the fast path. New `:source-readability` Gradle module (Readability4J extraction, single-chapter library fiction, `defaultEnabled = true`) catches anything else at confidence `0.1f`. **ACTION_SEND share-intent** on MainActivity (text/plain) lets browsers, RSS readers, podcast clients, and social-share menus hand URLs to storyvox directly — the activity routes to Library with the URL surfaced as a query arg via `DeepLinkResolver.libraryWithShare`, and the Library screen opens the Add-by-URL sheet pre-populated. v1 ships with the FAB-launched sheet as the entry point; a dedicated Magic-add card at the top of Library is a follow-up.

### Fixed — QA backlog drain (13 of 14 cleared; #461 verified-already-fixed by #468)
- **Player back-arrow content-desc was 'Settings'** (#474, closes #437) — `onBack` plumbed end-to-end with proper a11y label.
- **Notion silently fell back to TechEmpower Resources DB when no token** (#474, closes #443 + #447) — per JP design call, fallback now points at techempower.org's actual Notion content with a clear demo banner in Browse and a "Database ID (TechEmpower demo)" label in Settings; once user adds a token, the plugin switches to their root.
- **AO3 description showed `&amp;amp;`** (#474, closes #444) — fixed-point HTML entity decoder + 6 regression tests.
- **Browse Search icon disappeared on AO3 chip** (#474, closes #445) — added `BrowseTab.Search` to AO3's supportedTabs.
- **'Add by URL' copy was Royal-Road-only** (#474, closes #446) — generalised hint with supported-source one-liner; carries a `TODO(#472)` placeholder that the magic-link PR replaced with the cascade-resolver.
- **Live Radio timecode stuck at 0:00** (#474, closes #448) — scrubber hidden + pulsing "LIVE" pill when `state.isLiveAudioChapter`.
- **Radio fiction author rendered as country name** (#474, closes #449) — `author = "Live radio"` with regression test.
- **Chapter subtitle 'Ch. 0 · Chapter 1' indexing inconsistency** (#474, closes #453) — new `formatChapterLabel(index, title)` helper, 1-indexed + redundant-prefix detection.
- **Settings 'Save' accepted empty required fields silently** (#474, closes #455) — per JP design call, Outline + Notion Save now show a transient toast naming skipped fields; defaults applied silently to the model. No save-blocking, no Material error chip — lighter touch than full Material 3 supported-error.
- **Notebook empty-state pointed at a missing Chat CTA** (#474, closes #456) — copy softened.
- **RSS Browse chip showed empty screen with no helper copy** (#474, closes #458) — explicit empty-state with "Add a feed" CTA.
- **GitHub: 'The Cartographer's Lantern' showed 0 chapters** (#474, closes #460) — `fictionDetail` now falls back to root `SUMMARY.md` when `src="src"` configuration points elsewhere; regression test added.
- **GitHub plugin title hierarchy confused repo name vs README title** (#474, closes #463) — byline now prefixed with "by " on FictionDetail.

### Under the hood
- New `:source-readability` Gradle module with `ReadabilityExtractor`, `ReadabilityFetcher`, and Hilt `ReadabilityModule`. `@SourcePlugin(defaultEnabled = true)` so it auto-registers in the registry without an `:app` build.gradle change.
- `UrlResolver` cascades through `UrlRouter` → `UrlMatcher` impls → `:source-readability`. `RouteCandidate(confidence: Float, label: String, ...)` lets ranking work when multiple backends both claim a URL.
- `MultipleMatches` variant on `AddByUrlResult` for the rare ≥2-candidates-above-0.5 case — v1 auto-picks the top; chooser modal is UI follow-up if discoverability needs it.
- 14-commit QA bundle was rescued via cherry-pick after a parallel-agent branch contamination event (qa-bundle and magic-link both worked the same repo without worktrees and stepped on each other). New `feedback_parallel_agents_need_worktrees` memory captures the lesson; future parallel runs use `isolation: "worktree"` per agent.

## [0.5.39] — 2026-05-14

### Added
- **Nav restructure: Settings becomes a primary destination** (#469) — the bottom bar collapses from 5 destinations to `{Library, Settings}`. The Library tab now hosts 5 scrollable sub-tabs: `{Library, Browse, Follows, Inbox, History}`. Browse / Follows / Inbox / History are no longer separate bottom destinations — they fold into Library as embedded subscreens (`embedded: Boolean` param on Browse/Follows skips their own TopAppBar when rendered under Library). The legacy `BROWSE` / `FOLLOWS` routes are preserved for deep-link survival. Pins via `NavStructureTest` (10 cases) + `LibraryTabCollapseTest` (8 cases).
- **InstantDB sync for settings + secrets** (#470, partial follow-up to #360) — `:core-sync` now round-trips 50+ allowlisted DataStore prefs (theme override, voice tuning, AI config, source toggles + the Phase-3 plugins-enabled map, inbox mute, sleep timer, per-backend non-secret config like Wikipedia lang / Notion db id / Discord coalesce-minutes / Outline host / Memory Palace host) as a single last-write-wins JSON blob to InstantDB. Tier 2 widens the encrypted-secret allowlist to fold in Notion / Discord / Outline tokens that already lived in `EncryptedSharedPreferences`. Encryption posture: client-side envelope with PBKDF2-HMAC-SHA256/600k + AES-GCM-256 and per-user deterministic salt; no-passphrase hard-fails Permanent (never plaintext-fallbacks). Device-local flags (`SIGNED_IN`, `LAST_WAS_PLAYING`, milestone gates) are explicitly excluded.
- **Settings hub finished — 7 dedicated subscreens** (#471, closes the v0.5.38 #440 follow-up) — the SettingsHub used to route only 5 of 13 cards to focused subscreens (Voice library, Plugins, AI sessions, Pronunciation, Debug); the other 7 fell back to a legacy long-scroll Settings page. This release adds dedicated subscreens for `Voice & Playback`, `Reading`, `Performance`, `AI`, `Account`, `Memory Palace`, and `About`, each composed from a shared `SettingsSubscreenScaffold`/`Body` wrapping the row composables in `SettingsScreen.kt` (those went from `private` to `internal`). Legacy `SettingsScreen` stays as an "All settings" power-user fallback. 21 new tests across `SettingsSubscreenContractTest`, `SettingsSubscreenRoutesTest`, and `SettingsHubSectionsTest`.

### Fixed — QA UX-blocker bundle
- **Notebook + Pronunciation Add dialogs didn't shift focus on second-field tap** (#468, closes #450) — `NotebookFocusContractTest` pins the regression.
- **Library grid empty in landscape orientation** (#468, closes #452) — `LibraryGridLandscapeTest` pins it.
- **RSS Add-feed dialog Add button was overlapped by the EditText** (#468, closes #459) — `BrowseRssAddSubmitTest` pins the layout fix.
- **FictionDetail chapter list rows were non-clickable** (#468, closes #461 — `priority: high`, blocked the read flow on every fiction).

### Under the hood
- `:core-sync` `SettingsSyncer` (new): exact-key allowlist of 50+ DataStore prefs (typed as `Set<String>`), per-backend non-secret config routed through each `*ConfigImpl` setter on apply. `SettingsSyncerTest` (round-trip on every allowlisted key + device-local-key rejection) + `SecretsSyncerExtensionTest` (Tier 2 allowlist widening) + 24 more = 26 new tests.
- CI workflow gained a self-hosted-runner-specific step that copies `local.properties` from `/home/jp/Projects/storyvox/` into the runner's checkout pre-assemble, so release APKs bake the real `INSTANTDB_APP_ID` into `BuildConfig` instead of the `PLACEHOLDER` sentinel.

## [0.5.38] — 2026-05-14

### Fixed — QA bundle from the exhaustive Flip3 pass
- **Browse only showed 5 of 17 backend chips on fresh install** (#467, closes #436) — 12 `@SourcePlugin(defaultEnabled = false)` annotations were wrong defaults on the new backends (GitHub, Memory Palace, EPUB, Outline, AO3, Standard Ebooks, Wikipedia, Wikisource, Hacker News, arXiv, PLOS, Discord). Fresh installs of v0.5.32–v0.5.37 saw only Gutenberg / Royal Road / RSS / Notion / Radio in the chip strip. Flipped all 12 + matching `LegacySourceKeys.ALL` to `defaultEnabled = true`. Upgrade users with explicit OFF settings retain their preferences via the legacy-key migration. 7 new tests pinning the default + migration contract.
- **Library had two adjacent tab rows with overlapping labels** (#467, closes #438) — top strip had `All / Reading / Inbox / History` (section selector), second strip had `All / Reading / Read / Wishlist` (shelf selector). Material's anti-pattern: same string in two nested navigation surfaces. Collapsed to 3 top tabs (`Library / Inbox / History`); the shelf chip row inside Library tab is now the canonical Reading affordance. 6 new tests.
- **Settings gear icon dumped users into Voice & Playback section** (#467, closes #440) — no Settings root menu. New `SettingsHubScreen` + `SETTINGS_HUB` nav route landing on a hub of 13 brass-edged section cards: Voice library, Plugins, AI sessions, Pronunciation, Debug (dedicated subscreens for 5; legacy long-scroll for the other 7 + an "All settings" escape hatch). 6 new tests.
- **Gutenberg chapter playback hung at 0:00 'Buffering…' indefinitely** (#467, closes #442 — `priority: high`) — two compounding causes: (a) `GutenbergSource.stripTags` was a permissive `<[^>]+>` regex that left `<head>` / `<script>` / `<style>` contents in the output, so the synth queue saw Project Gutenberg's inline CSS instead of prose and Piper synthesized punctuation-heavy CSS while the UI showed `state=PLAYING / position=0` indefinitely; (b) `EnginePlayer.loadAndPlay` had no zero-sentences guard, so the silent failure mode had no diagnostic. Fixed both — pre-strips non-visible regions in `stripTags` (DOTALL, case-insensitive) + typed `PlaybackError.ChapterFetchFailed("This chapter has no readable text…")` early-return with a hot-path synth breadcrumb log. 7 new tests.
- **Calliope v0.5.00 milestone celebration window closes after v0.5.5** (#451, closes #435 + #439) — the dialog had been firing on fresh installs of every build past v0.5.0 with the hand-tuned headline "storyvox 0.5.00" because `Milestone.qualifies()` returned true for anything `≥ 0.5.0`. Fresh installer on v0.5.36 saw a stale headline; reported as "onboarding splash shows hardcoded 0.5.00". Fix: added an upper bound. Users who installed during the window keep their dismissal flag; fresh installers past v0.5.5 silently never see the dialog. Dialog code stays for history.

### Backend defaults
Post-#436 fix, fresh installs see all 17 backends in the Browse chip strip: Royal Road, GitHub, Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource, Radio, Notion, Hacker News, arXiv, PLOS, Discord. Plus the 12 specialty content surfaces (audio-stream Radio category, the AI heavies trifecta from v0.5.30–v0.5.36, etc.).

## [0.5.37] — 2026-05-14

### Fixed
- **Pause/Resume on radio chapters actually pause/resume now** (#434) — `PlaybackController.pause()` hard-called `EnginePlayer.pauseTts()`, which tears down the TTS pipeline only. For audio-stream chapters (KVMR + the v0.5.32 radio backend), the sibling ExoPlayer kept streaming on top of the dead TTS state. Pause was a visual lie on radio. Fix: new `EnginePlayer.pauseRouted()` checks `isLiveAudioChapter` and drops `playWhenReady` on `audioStreamPlayer` for radio, falls through to `pauseTts()` for TTS. Same routing added at the top of `EnginePlayer.resume()` so Play button recovers correctly. Reported by JP on tablet R83W80CAFZB v0.5.36.

## [0.5.36] — 2026-05-14

### Added
- **AI chat multi-modal image input** (#433, closes #215) — attach button in the chat composer launches SAF `OpenDocument` for `image/*`. Picked image is downscaled to 1280px-long-edge + JPEG q=85 + base64-encoded via a new `ImageResizer` (same pattern as the screenshot-compress hook for the multimodal API safety envelope), then sent as a `LlmContentBlock.Image` alongside the text in the LLM request. Composer shows a 200dp thumbnail above the text input with an x to remove; the user's message bubble renders the image inline via Coil. Last of the three AI heavies in the v0.5.30–v0.5.36 wave (#217 cross-fiction memory ✓, #216 function calling ✓, #215 image input ✓).
- **Provider coverage v1**: Anthropic (Claude direct + Teams OAuth) and OpenAI return `supportsImages = true` and serialize image content blocks natively. Vertex / Bedrock / Foundry / Ollama silently drop image parts and the chat surface shows a one-shot info banner ("Image input not supported on this provider — sending text only"); per-provider wiring is straightforward follow-up.

### Under the hood
- New `LlmContentBlock` sealed class in `:core-llm` with `Text(content)` + `Image(base64, mimeType)` variants. `LlmSessionRepository.chat()` and `chatWithTools()` now accept `userParts: List<LlmContentBlock>?` alongside the existing string-text path; DB rows stay text-only (image bytes are in-memory for the send round only — Room storage of base64 blobs is a follow-up if we want full chat-history rendering of past images).
- 8 new unit tests: `ContentBlocksTest` (sealed-class serialization), `ClaudeImageRequestTest` + `OpenAiImageRequestTest` (provider wire-format snapshots), `ImageResizerTest` (1280px clamp + JPEG q=85 round-trip + base64), two new `ChatViewModelTest` cases for composer state transitions + URI overlay.

## [0.5.35] — 2026-05-14

### Performance
- **Cold-launch ~14% faster on low-end Android** (#432, partial fix for #409). Wrapped every `@Inject lateinit var` in `StoryvoxApp` and `MainActivity` in `dagger.Lazy<T>` (except `HiltWorkerFactory` which WorkManager needs eagerly), and punted `workScheduler.ensurePeriodicWorkScheduled()` + `syncCoordinator.initialize()` off the main thread onto a shared `Dispatchers.IO` scope. Measured on Galaxy Tab A7 Lite (Helio P22T, 2.0 GHz Cortex-A53): **6825ms → 5861ms** averaged over 5 cold launches; Choreographer skips dropped from 160 frames → 142 frames. No regression on Z Flip3 (1240ms → 1200ms). The remaining ~5.9s on the tablet is dominated by Compose first-recomposition cost (~2.4s of frame-skip) — needs a Macrobenchmark target to slice further; #409 stays open for that ongoing work.

## [0.5.34] — 2026-05-14

### Added
- **KittenTTS as the third in-process voice family** (#431, closes #119) — smallest tier alongside Piper (compact) and Kokoro (multi-speaker). The fp16 nano model is ~24 MB across 3 flat files (model + sentencepiece config + voices), shared across all 8 speakers (F1–F4 / M1–M4, en_US). Lives between Kokoro and Azure in the Voice Library as a "Kitten (Lite)" section. Designed for slow devices where even Piper struggles. Cross-repo: upstream `techempower-org/VoxSherpa-TTS` v2.8.0 ships `KittenEngine` mirroring `KokoroEngine`'s multi-speaker shape via sherpa-onnx's existing `OfflineTtsKittenModelConfig`. JitPack coordinate bumped from v2.7.14 → v2.8.0.
- `VoiceManager.kittenSharedDir()` + a download branch for the shared-model sentinel pattern (single download → 8 speakers); EnginePlayer dispatcher routes `EngineType.Kitten(speakerId)` at four sites including Tier 3 parallel-synth via a new `secondaryKittenEngines` pool. 10 new unit tests covering catalog contract (8 voices, en_US, Low tier), engine-dispatcher routing (Kitten vs Piper vs Kokoro), and VoiceManager download branch.

## [0.5.33] — 2026-05-14

### Added
- **AI function calling — 5 tools** (#430, closes #216) — the chat AI can now actually *do* things in storyvox, not just answer questions. Five v1 tools wired end-to-end:
  - `add_to_shelf(fictionId, shelf)` — Reading / Read / Wishlist
  - `queue_chapter(fictionId, chapterIndex)` — sets the play queue to a specific chapter
  - `mark_chapter_read(fictionId, chapterIndex)` — flips the read state
  - `set_speed(speed)` — clamped to [0.5, 2.5]
  - `open_voice_library()` — navigates to Voice picker
- **Per-tool brass card in chat** — when a tool fires, a small brass-edged card renders in the chat stream showing in-flight / success / error state. Examples: "Adding *Frankenstein* to your Reading shelf…" → "✓ Added." or "✗ Couldn't find fiction with id 'xyz'." Card collapses after settling.
- **Settings → AI → "Allow the AI to take actions" toggle** — default ON. Gates the catalog advertisement to the LLM; with the toggle OFF the AI gets a tools-free system prompt and falls back to text replies.
- **Provider coverage v1**: Anthropic (Claude direct + Teams OAuth) + OpenAI both implement the full model→tool→model loop bounded to 5 rounds. Vertex / Bedrock / Foundry / Ollama get a graceful default `chatWithTools` fallback (plain text reply, no tool support) until each provider's tool-format wiring lands as follow-ups.

### Under the hood
- New `:core-llm/tools/` module sub-tree: `ToolSpec`, `ToolCatalog`, `ToolHandler`, `ChatStreamEvent` (rich flow type that carries text deltas + tool-call events + tool-result events in one stream). Provider chat clients gain `chatWithTools()` alongside the existing `chat()`.
- `:feature/chat/tools/ChatToolHandlers` binds the 5 tool names to typed suspend functions wired to the existing `ShelfRepository`, `ChapterRepository`, `PlaybackControllerUi`, and `NavController` — pure execution, no LLM logic. The wiring lives outside `:core-llm` so the chat domain owns the side-effects.

### Tests
- 18 new unit tests: catalog contract (5 tools present + valid JSON schemas), Anthropic + OpenAI wire-shape snapshots (incl. tool-result round-trip via MockWebServer), handler clamp/reject paths, `set_speed` range enforcement, navigation event firing for `open_voice_library`, unsupported-provider fallback behavior.

## [0.5.32] — 2026-05-14

### Added
- **Magical voice settings icon on the play screen** (#428, closes #418) — replaces the buried `⋮` overflow's voice-settings section with a brass-edged soundwave-with-sparkle icon at the top bar. Tap → Material 3 bottom sheet with 5 live-applying rows (Speed / Pitch / Voice picker chip / Sentence silence / Sonic high-quality) + an "Advanced" expander deep-linking to Voice Library for the v0.5.30 per-voice lexicon + Kokoro phonemizer pickers. Long-press → straight to Voice Library. `PlayerOptionsSheet` split into `PlayerOverflowSheet` (sentence step, sleep timer, bookmark, recap, AI chat) — voice rows moved out cleanly. 5 new tests covering row spec, live-audio pitch hiding, speed-first ordering, long-press routing, voice-chip label formatting.
- **Generalized `:source-kvmr` → `:source-radio` with Radio Browser API search** (#429, closes #417) — rename + reshape lands 5 curated stations (KVMR 89.3, Capital Public Radio KXPR, KQED 88.5, KCSB 91.9, SomaFM Groove Salad) plus a new Search tab on Browse → Radio backed by Radio Browser's free `de1.api.radio-browser.info` directory (30k+ stations worldwide). User can star Radio Browser results to add them to their library — starred station descriptors persist in a dedicated `storyvox_radio_starred` DataStore (full record stored, so a star survives the upstream directory culling the station). `KvmrEnabledToRadioEnabledMigration` seeds `pref_source_radio_enabled` from the legacy `pref_source_kvmr_enabled` on first read; Hilt `RadioModule` dual-binds `RadioSource` under both `radio` and `kvmr` StringKeys so persisted `kvmr:live` library rows resolve unchanged. KNCO trimmed from v1 (no stable stream URL discoverable). 24 new tests.

### Fixed
- **Light theme selection had no effect at the renderer** (#427, closes #412) — `MainActivity` called `LibraryNocturneTheme { ... }` without arguments, so the theme wrapper's `darkTheme` defaulted to `isSystemInDarkTheme()` and ignored the saved `themeOverride` preference. Settings → Reading → Theme picker (System / Dark / Light) saved the selection to `pref_theme_override` and showed checked in the UI, but the renderer never read it. Light selection was cosmetic only. Fix: inject `SettingsRepositoryUi` into MainActivity, collect the `settings` flow as State, map `ThemeOverride { System, Dark, Light }` to an explicit `darkTheme` Boolean, pass to `LibraryNocturneTheme`. System falls back to `isSystemInDarkTheme()`; Dark/Light force the corresponding palette regardless of device setting. **High-pri regression that masked the parchment-cream daytime aesthetic entirely.**

### Backend count
- **17 fiction backends** total still (Radio is the rename of KVMR + the new search surface; not a separate addition). The Radio backend now scales from "one station" to "any of 30k+ via Radio Browser star-to-add", so the same source slot is much more useful.

## [0.5.31] — 2026-05-14

### Added
- **Plugin manager Settings tab** (#404) — new Settings → Plugins screen iterating `SourcePluginRegistry.descriptors`. Three category sections: Fiction sources (16 in-tree), Audio streams (KVMR; v2 will grow this as the radio backend generalization in #417 lands), Voice bundles ("Coming in v2" placeholder until the voice bundle registry lands as a follow-up). Each plugin renders as a brass-edged card with a brass monogram icon, plugin name + description, capability chips (Search / Follow / Audio / Text), brass-edged switch, and tap-for-details modal sheet showing capabilities, plugin id, and source URL. Top of the screen has a search input (substring on displayName / description / id) and three filter chips (On / Off / All). Adding a new backend (a `@SourcePlugin`-annotated class) automatically surfaces a new card — no edit to the screen file needed.
- **`@SourcePlugin.description` + `@SourcePlugin.sourceUrl`** (#404) — annotation gains two new fields backfilled across all 17 in-tree backends. The KSP processor emits both into the generated descriptor; the plugin manager card uses `description` for the subtitle and the details sheet uses `sourceUrl` for the "where this plugin reads from" row.

### Changed
- **Plugin seam Phase 3 — single source of truth** (#384, last phase) — the legacy `BrowseSourceKey` enum is deleted; the 17 hand-rolled `sourceXxxEnabled` boolean fields on `UiSettings` are deleted; the 17 `setSourceXxxEnabled` setters on `SettingsRepositoryUi` and `SettingsViewModel` are deleted; the `BrowseScreen` + `BrowseViewModel` exhaustive `when (BrowseSourceKey)` branches collapse into id-keyed lookups + a registry-iteration picker. The dual-write that mirrored each per-source toggle into both a JSON map and a per-id boolean key is gone — there's one shape now (`sourcePluginsEnabled: Map<String, Boolean>`). A one-shot migration on first launch of v0.5.31 reads the legacy `pref_source_*_enabled` boolean keys ONCE and seeds the JSON map; subsequent toggles go through `SettingsRepositoryUi.setSourcePluginEnabled(id, enabled)`. New `BrowseSourceUi` side-table in `:feature/browse` carries the per-source UI hints (chip strip label, supported tabs, filter sheet shape, search hint copy) keyed by `SourceIds` constant — adding a new backend is one row here next to the `@SourcePlugin` annotation backfill, not 17 touchpoints across modules. **The "~17 touchpoints per new backend" → "~4" goal from #384's opening statement now holds.**
- **Settings → Library & Sync → Sources sub-card** simplified — the 17 inline per-backend toggle rows are gone; the sub-card now shows one "Plugins" link row (→ Settings → Plugins) plus the inline config rows for per-plugin configuration that doesn't belong in a checkbox toggle (EPUB folder picker, Outline host + API key, Wikipedia language code, Notion db + token, Discord bot token + server + coalesce window). The plugin manager handles every enable/disable + capability surface.

### Tests
- 6 Phase 3 tests: `RegistryDescriptorsAliasTest` (alias contract + description / sourceUrl fields), `BrowseSourceUiTest` (chipLabel / supportedTabs / filterShape / searchHint for all 17 ids + fallback), `NoBrowseSourceKeyRegressionTest` (greps the production tree to fail the build if `BrowseSourceKey` ever leaks back outside kdoc), `SettingsRepositorySourcePluginsTest` (round-trip for all 17 ids, legacy-key first-launch migration, no-dual-write into legacy booleans, forward-compat for unknown ids).
- 4 plugin manager tests: `PluginManagerLogicTest` covering `filterPlugins` (On / Off / All chip × search query × composition) and `groupPluginsForManager` (category split + order preservation + empty input).

## [0.5.30] — 2026-05-14

### Added
- **Discord as the 17th fiction backend** (#416, closes #403) — first chat-platform backend. Server → top-level filter, channel → one fiction, message → one chapter (optionally coalescing consecutive same-author messages within a configurable 1-30 min window, default 5). Auth is user-supplied bot token: user creates a Discord app, generates a bot token, invites their bot with `READ_MESSAGE_HISTORY` scope, pastes in Settings. No bundled token, no auto-join, no selfbot. OkHttp wrapper over 4 endpoints with structured 429 + `Retry-After` handling. 6 new unit tests covering coalesce edge cases + JSON-parse fixtures. Default OFF on fresh installs.
- **Cross-fiction AI memory + per-book Notebook tab** (#414, closes #217) — `FictionMemoryEntry` Room entity + `FictionMemoryRepository`, prompt-builder appends a "Cross-fiction context" block listing entries from OTHER fictions where detected names match in the current chat turn (capped ~500 tokens, oldest dropped). Per-fiction "Notebook" sub-tab on `FictionDetailScreen` shows recorded entities (characters / places / concepts) with manual-add / delete; Settings → AI → "Carry memory across fictions" toggle (default ON). Population is regex-based name detection on AI replies for v1 — approximate but cheap; follow-ups for structured LLM-call extraction, InstantDB sync, per-author/world partitioning, confidence scoring documented in the PR. Room schema v8 → v9 (additive). 25 net new tests.
- **Per-voice lexicon + Kokoro phonemizer language overrides** (#415, closes #197 #198) — Settings → Voice → per-active-voice Advanced expander gains SAF `.lexicon` file picker (#197 — IPA pronunciation overrides for hard-to-pronounce names; great for Royal Road's "Wei Wuxian" / "Lianhua" / "Aelindra" pain) and Kokoro phonemizer-lang chip strip (#198, 9 documented Kokoro codes). Per-voice (not global): each voice carries its own overrides; switching active voice re-applies the bridge static fields before the next chapter renders. **Cross-repo work**: upstream `techempower-org/VoxSherpa-TTS` v2.7.14 ships `voiceLexicon` + `phonemizerLang` static volatile fields; JitPack coordinate bumped from v2.7.13. 24 new tests across `VoiceEngineQualityBridgeTest` + `SettingsRepositoryVoiceLexiconLangTest` + `LexiconPathSafParseTest`.

### Fixed
- **`NetworkOnMainThreadException` on Wikipedia + Wikisource first chip tap** (#421, closes #419) — both backends' `getRaw` were non-suspend, doing blocking `client.newCall(req).execute()` without a `withContext(Dispatchers.IO)` wrapper. Same class of bug as the earlier Gutenberg fix. Crash caught by the QA-rerespawn agent on tablet + Flip3; logcat stacks captured on #419. **High-pri regression that affected two of the 16 backends.**

### Build state
- Four-PR bundle merged in order: #421 (smallest fix, no overlap) → #416 (Discord, new module + chip) → #415 (VoxSherpa knobs, voice picker) → #414 (cross-fiction memory + Room v8 → v9 migration). Each PR was independently CI-green before the wave landed; GitHub's mergeStateStatus recomputed BEHIND between each merge and the next merge fast-forwarded cleanly with no manual rebase needed.
- **17 fiction backends** total now: Royal Road, GitHub, Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource, KVMR (audio-stream), Notion, Hacker News, arXiv, PLOS, Discord (new).

### Infrastructure (this session, not a release feature but worth recording)
- New global PostToolUse Bash hook at `~/.claude/hooks/screenshot-compress.sh` — auto-downscales any fresh PNG in `~/.claude/projects/` scratch dirs to JPEG q=80 / max 1280px on the long edge. Avoids the "400 Could not process image" multimodal API failure that killed the first QA agent. Verified end-to-end via `hook-tester` subagent. Lives outside storyvox but unblocks future tablet/phone QA passes.

## [0.5.29] — 2026-05-14

### Added
- **Wear OS Library Nocturne theme + circular scrubber** (#406, closes #192) — `:wear` module gets a brass-on-warm-dark theme matching the phone/tablet. `NowPlayingScreen` now wraps a Coil-loaded cover with a brass `CircularScrubber` on round watches (square form factor falls back to a brass-tinted linear scrubber). 3-button transport row wired to the existing `PhoneWearBridge`. Five `@Preview` entries cover round/playing, round/paused, round/buffering, small-round, and square so the visual diff is reviewable from the preview pane.
- **Voice library search + language filter** (#413, closes #264) — sticky search bar + horizontally-scrolling language filter chips at the top of the voice picker. Type-to-filter on voice display name (200ms debounce); chips derived dynamically from installed-voice languages (English-first, then alphabetical). Filters apply via `combine(installedVoices, query, language)` in the ViewModel; the existing favorites star + Starred surface still pin voices on top. Closes the "1188 voices, no search, unusable on Flip3" pain. 15 new `VoiceFilterTest` cases.
- **Room+Robolectric DAO test layer** (#410, closes #48) — 43 new DAO tests covering `FictionDao`, `ChapterDao`, `PlaybackDao` with slim-projection regression coverage, `@Transaction` boundaries, and `CASCADE` behavior. Tests run on a real in-memory Room database under Robolectric so SQL fidelity is preserved across the dependency graph. Pure additive — no production code touched.

### Changed
- **Browse source picker is now a scrollable `LazyRow` of `FilterChip`s** (#411, closes QA-found #407) — replaces the previous segmented-button row which mid-word-wrapped on tablet and silently hid the rightmost chips (arXiv / PLOS / Notion couldn't be reached on narrow viewports). Active chip carries the brass active-state coloring; the row pans horizontally so every backend stays reachable regardless of viewport width.

### Refactored
- **`AuthRepositoryImpl` now uses `@ApplicationScope CoroutineScope`** (#405, closes #30) — replaces the bare-`CoroutineScope` `init { ... }` block flagged as deferred-to-v1.0-hardening. Injected scope uses `SupervisorJob` for structured concurrency and is trivially swappable with `TestScope` in unit tests. No behavior change at runtime; auth init is structurally identical from the user's perspective.

### Build state
- All five PRs (#405 / #406 / #410 / #411 / #413) shipped as a bundle merge with no inter-PR conflict — each PR was independently CI-green before the wave landed, and the wave merged in dependency order (refactor → tests → small fix → voice picker → Wear) so cross-touching files (`BrowseScreen.kt`, voice picker) re-rebased cleanly between merges.

## [0.5.28] — 2026-05-13

### Added — four new backends, all using the v0.5.27 `@SourcePlugin` pattern from day one
- **Wikisource** (#376, #399) — Wikimedia project for transcribed public-domain texts (Shakespeare, classic novels, historical documents). Browse landing reads `Category:Validated_texts` (the double-proofread quality tier); free-form search via MediaWiki Action API. Multi-part works walked via `/Subpage` traversal; single-page works fall back to Wikipedia-style heading_1 splits. 14 unit tests. Default OFF on fresh installs.
- **arXiv** (#378, #400) — open-access academic pre-print server (physics, math, CS). Default category for browse is `cs.AI`; free-form search via the public `export.arxiv.org/api/query` Atom feed. Each paper is one fiction; v1 chapter is the abstract + title + author byline + comments rendered from the `arxiv.org/abs/<id>` HTML page. Full-PDF body extraction is an explicit follow-up scope cut. 12 unit tests. Default OFF.
- **Hacker News** (#379, #401) — front-page tech-news threads as single-chapter fictions. Popular surfaces the first 50 of HN's top-stories Firebase list; Search hits the Algolia HN Search API. Link stories include the title + URL + top 20 comments (threaded with `—` depth prefixes); Ask HN / Show HN use the story `text` field directly. 8 unit tests. Default OFF.
- **PLOS / Public Library of Science** (#380, #402) — open-access peer-reviewed science (PLOS ONE, Biology, Medicine, Comp Biology, Genetics, Pathogens, Neglected Tropical Diseases). Browse landing reads recent PLOS ONE sorted by publication date; Search hits the same Solr endpoint with free-form `q=`. Each article (one DOI) is one fiction; v1 chapter is the abstract + first ~3 body sections. 11 unit tests. Default OFF.

### Total backend count
- **16 fiction backends** now: Royal Road, GitHub (curated registry), Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource (new), KVMR (audio-stream), Notion, Hacker News, arXiv (new), PLOS (new). All four new backends register via `@SourcePlugin` and surface in the `SourcePluginRegistry` automatically — adding new backends is now ~4 touchpoints instead of 17 (per the Phase 2 #384 work that shipped in v0.5.27).

### Under the hood
- Bundle merge orchestrated per `feedback_no_eager_merge_in_bundle`: all four PRs (#399, #400, #401, #402) opened in parallel from worktrees, merged in order (Wikisource → HN → arXiv hand-rebased → PLOS hand-rebased) once each backend's CI was independently green. arXiv and PLOS branches required hand-rebase + union-resolve of additive enum + Settings UI + test-fake additions; the agent-curated commits and tests survived intact.
- Five test fakes (`ChatViewModelTest`, three `SettingsViewModel*Test` flavors, and `RealPlaybackControllerUiTest`) updated with `setSourceArxivEnabled` / `setSourcePlosEnabled` stubs to match the new `SettingsRepositoryUi` interface members.

## [0.5.27] — 2026-05-13

### Added
- **Plugin seam — Phase 2: 11 remaining backends migrated to `@SourcePlugin`** (#384) — every fiction source (`royalroad`, `github`, `mempalace`, `rss`, `epub`, `outline`, `gutenberg`, `ao3`, `standard_ebooks`, `wikipedia`, `notion`) now carries a `@SourcePlugin(id=…, displayName=…, defaultEnabled=…, category=Text, supportsFollow=…, supportsSearch=…)` annotation on its `FictionSource` impl, with `ksp(project(":core-plugin-ksp"))` wired in each module's `build.gradle.kts`. The KSP processor emits one `@Provides @IntoSet SourcePluginDescriptor` Hilt module per annotated class, so the `SourcePluginRegistry` singleton now exposes the full 12-plugin roster (the 11 fiction backends + KVMR from Phase 1). Existing `@IntoMap @StringKey` Hilt bindings are intentionally kept — Phase 2 is additive over the legacy wiring; Phase 3 will delete the legacy `BrowseSourceKey` enum and the per-source `sourceXxxEnabled` booleans on `UiSettings` once the registry-driven UI lands.
- **`SourcePluginRegistry` duplicate-id guard** (#384) — registry `init` block now hard-fails at app startup with an `IllegalStateException` listing the offending ids when two `@SourcePlugin` annotations declare the same id. Catches a copy-paste mistake on a fresh-source addition before Hilt's silent which-one-wins multibinding behaviour can ship.
- Two new `SourcePluginRegistryTest` cases: the duplicate-id guard, and a Phase 2 roster contract test that asserts the registry surfaces all 12 expected `SourceIds.*` ids via `byId` and matches the expected size.



### Added
- **Cross-source Inbox tab in Library** (#383) — new fourth Library sub-tab (`All / Reading / Inbox / History`) surfaces a chronological feed of source-emitted events: "3 new chapters in The Wandering Inn", "KVMR live now", and (future) Wikipedia article updates. Tap a row to deep-link to the chapter/program; an unread-count badge sits on the tab itself. The feed is source-agnostic — one timeline across every backend that emits update events.
- **Per-source Inbox notification toggles** (#383) — Settings → Library & Sync gets an "Inbox notifications" sub-card with one switch per emitting backend (`Royal Road`, `KVMR`, `Wikipedia`). Default ON; flipping a toggle OFF stops the backend's update emitter from writing rows to the cross-source feed without affecting library updates or the source's visibility in Browse.
- **`InboxRepository` + `inbox_event` table** (#383) — Room migration v7 → v8 lands the append-only event table backing the Inbox. Repository coalesces consecutive "N new chapters" events for the same fiction so the feed doesn't flood after a long offline gap. No FK to fiction/chapter — events deliberately survive parent-row removal so the user can still see "Wikipedia: X updated" after they unfollow the article.
- `NewChapterPollWorker` (#383) now records an `InboxEvent` row alongside its existing chapter-diff persistence whenever a polled source has missing chapters and the user hasn't muted that source in the Inbox toggles. KVMR live-program emission + Wikipedia article-diff emission are tracked as follow-ups — v1 wires the seam but only Royal Road's existing poll path emits today.
- **Vertex AI service-account JSON auth** (#219, #397) — Settings → AI → Vertex gains a SAF JSON file picker alongside the existing API-key field. Picked service-account JSON is parsed/validated, encrypted at rest in `EncryptedSharedPreferences`, and used to mint 1-hour OAuth access tokens on demand (JWT-bearer RFC 7523, signed in-process with `java.security` — no `google-auth-library` dep). Tokens cached until ~5 min before `expires_in` and refreshed transparently. Mutually exclusive with the API-key mode at the storage layer. 20 new tests cover parse validation, JWT sign+verify, token cache lifecycle, and end-to-end Vertex SA dispatch via MockWebServer.
- **Plugin seam — Phase 1 scaffolding** (#384, #396) — `@SourcePlugin` annotation in `:core-data`, a KSP SymbolProcessor in the new `:core-plugin-ksp` module that emits Hilt `@IntoSet` factories for each annotated `FictionSource`, and a `SourcePluginRegistry` singleton that consumes the multibinding. New `pref_source_plugins_enabled_v1` JSON map preference (id → enabled) seeded from the existing per-source `SOURCE_*_ENABLED` boolean keys via the one-shot `SourcePluginsMapMigration`, with dual-write from every legacy setter so the old `UiSettings.sourceXxxEnabled` observers stay in sync. `:source-kvmr` migrated as the worked example (one `@SourcePlugin(id="kvmr", …)` line + a `ksp(project(":core-plugin-ksp"))` dep — its existing `@IntoMap @StringKey` Hilt binding is intentionally kept for Phase 1). Phase 2 (follow-up PRs) migrates the remaining 11 backends and switches BrowseScreen + Settings to iterate the registry.

## [0.5.25] — 2026-05-13

### Added
- **Anonymous Notion-site reader mode** (#393, closes the v0.5.24 known limitation) — `:source-notion` now reads public-shared Notion pages via the *unofficial* `www.notion.so/api/v3` surface (`loadPageChunk`, `queryCollection`, `syncRecordValuesMain`, `getPublicPageData` — the same set [react-notion-x](https://github.com/NotionX/react-notion-x)'s `notion-client` package uses). Zero setup: a fresh install opens Browse → Notion and immediately surfaces TechEmpower's content as narratable audio, with no integration token required.
- **Four-fiction TechEmpower layout (revised mid-cycle)** — Browse → Notion shows **four tiles**, one per top-level section of the techempower.org navigation. Each section is its own fiction, and each article inside it is its own chapter:
  - **Guides** — 8 chapters, one per curated guide page (How to use TechEmpower, Free internet, EV incentives, EBT balance, EBT spending, Findhelp, Password manager, Free cell service). Chapter order matches `techempower/site.config.ts` `pageUrlOverrides`.
  - **Resources** — N chapters (~80), one per row in the TechEmpower Resources database (queried via `queryCollection`). Each chapter renders the row's underlying Notion page content.
  - **About** — single-chapter fiction with the About page content.
  - **Donate** — single-chapter fiction with the Donate page content.
  This is a course correction from the v0.5.25-rc design that landed in PR #394 as a single-fiction-multi-chapter layout — JP redirected to "four books, each article a chapter" mid-cycle. The delegate, NotionDefaults, and AnonymousNotionDelegateTest were rewritten before tagging v0.5.25 so the released APK has the new shape.
- **`NotionConfig` mode enum** — new `NotionMode { ANONYMOUS_PUBLIC, OFFICIAL_PAT }` selects the read path. Anonymous mode reads any public-shared root page id; PAT mode keeps the original integration-token + database-id flow for private workspaces. The mode is implicit: blank token → anonymous, non-blank token → PAT. Existing users with a stored PAT keep their current experience unchanged.

### Fixed
- **Stale "TODO placeholder" rejection in `NotionApi.requireConfigured`** — v0.5.23 shipped a check that fast-failed when `databaseId == TECHEMPOWER_DATABASE_ID`; v0.5.24 replaced that id with the real TechEmpower Resources DB but left the check, silently breaking the bundled default for anyone with a PAT shared to the Resources DB. v0.5.25 removes the equality check; gating is now token presence alone in PAT mode and root-page-id presence in anonymous mode.

### Implementation
- `NotionUnofficialApi` (new) — OkHttp client for the four `/api/v3` endpoints with hand-crafted JSON bodies (the queryCollection loader shape is deeply nested; full kotlinx-serialization round-trips would be more code than the bodies). Process-lifetime in-memory cache keyed on hyphenated page id; deduplicates round-trips within a Browse → detail flow. Every HTTP call is wrapped in `withContext(Dispatchers.IO)` so the source can be safely called from any coroutine context.
- `AnonymousNotionDelegate` (new) — implements the FictionSource surface against `NotionUnofficialApi`. Builds a single Browse tile from the configured root page and resolves its chapter list from `NotionDefaults.techempowerChapters` (a hand-curated list of `ChapterSpec.Page` / `ChapterSpec.Collection` entries). Page chapters render their underlying Notion page's blocks via `renderPageBody`; collection chapters query the database via `queryCollection` and render a row-title list. Tombstoned blocks (`alive:false`) are filtered.
- `NotionConfigImpl` (modified) — persists a new `pref_notion_root_page_id` DataStore key. Defaults to `NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID` on first install; users can override via Settings.
- `NotionApi.requireConfigured` (bug fix) — removed the stale `databaseId == TECHEMPOWER_DATABASE_ID` placeholder check that v0.5.24 silently broke when it baked the real DB id into the same constant.
- 23 new unit tests in `AnonymousNotionDelegateTest` + `NotionUnofficialModelsTest` covering the recordMap envelope decode, decoration-array title extraction, page-id hyphenation, collection_view metadata read, chapter spec resolution (TechEmpower vs. generic), page-body rendering with tombstone filtering, collection-row title extraction + sorting, HTML/plain projection of the unofficial block types, mode-posture defaults, and tolerance for unknown top-level recordMap fields.

### Known caveats
- The unofficial `www.notion.so/api/v3` endpoints are undocumented; Notion may change their shape without notice. Storyvox decodes permissively (all block-payload fields are `JsonElement`) so unknown variants degrade silently rather than breaking parsing. Surface errors come back as structured `NotionUnofficialError` envelopes (`{isNotionError, errorId, name, message}`) which we map to standard `FictionResult.AuthRequired`/`NotFound`/`RateLimited`/`NetworkError`.

## [0.5.24] — 2026-05-13

### Fixed
- **`NotionDefaults.TECHEMPOWER_DATABASE_ID` now points at the real TechEmpower Resources database** (`2a3d706803c649409e74e9ce5ccd4c4b`, from `techempower/site.config.ts` line 48). Replaces the `TODO_FILL_IN_...` placeholder that shipped in v0.5.23. Users with their own Notion integration token shared with the database now see TechEmpower's Resources content as the default Notion fiction.

### Known limitation
- v0.5.24 still requires the user to paste a Notion integration token. The TechEmpower content lives at a publicly-shared `techempower.notion.site` URL, which is readable anonymously via Notion's *unofficial* `www.notion.so/api/v3/{loadPageChunk,queryCollection}` endpoints — but the official Notion REST API (which `:source-notion` currently uses) always returns 401 without auth, even for public content. v0.5.25 will land the anonymous-read mode + extend the tree to cover Guides + About + Donate alongside the Resources database (#393).

## [0.5.23] — 2026-05-13

### Added
- **Notion as a 12th fiction backend** (#391, closes #233 #390) — `:source-notion` module brings Notion databases into Browse alongside the other eleven backends. Database query → fiction list; pages split into chapters on every `heading_1`. PAT-based auth (Notion integration token), stored encrypted alongside the Outline / Palace / Royal Road / Wikipedia tokens. The token + database id are pasted in Settings → Library & Sync → Notion. 21 unit tests cover the API mappers, paginator, and config plumbing.
- **Notion default-on** (#390) — `sourceNotionEnabled = true` for fresh installs; existing users keep their stored preference. The default `databaseId` points at a `TODO_FILL_IN_TECHEMPOWER_DATABASE_ID` placeholder that returns a clean "Notion database id not configured" empty-state until JP pastes the real id (see [[NotionDefaults.kt]]).

### Changed
- Browse → Notion shows up as the rightmost source chip; Settings → Library & Sync has an inline NotionConfigRow with database-id + token fields.
- README + docs/index.md updated to "Twelve fiction sources" — the recurring "Six" / "Eleven" framing finally catches up to the actual surface.

### Known limitation
- The default `TECHEMPOWER_DATABASE_ID` is a placeholder string. The techempower.org website is backed by a Notion **page** (root page id `0959e445...`), not a database. Storyvox's `:source-notion` queries the Notion API's `databases/query` endpoint, which is a different object kind. A future change either (a) creates a separate Notion *database* in the techempower workspace to point at, or (b) extends `:source-notion` with a page-rooted hierarchy mode similar to the way `react-notion-x` traverses the website's root page. Until then, the default install shows the empty-state "Notion database id not configured (TODO placeholder still in use)" until the user pastes their own database id.

## [0.5.22] — 2026-05-13

### Infrastructure
- **Four sibling repos moved jphein → techempower-org** — storyvox-registry, speech-to-cli, cloud-chat-assistant, gnome-speaks. The `:source-github` Featured-row fetcher (`Registry.kt`) had a hardcoded URL pointing at `raw.githubusercontent.com/jphein/storyvox-registry/main/registry.json` — flipped to `techempower-org`. The old URL still 200s via GitHub's permanent raw redirect (so existing v0.5.21 installs keep working), but the new canonical URL is now baked into the v0.5.22 binary.
- USER_AGENT updated `storyvox/0.4 (+https://github.com/jphein/storyvox)` → `storyvox/0.5 (+https://github.com/techempower-org/storyvox)` so server-side logs / Plausible-style analytics see the canonical UA from this release forward.
- README + docs/index.md + docs/ROADMAP.md + settings.gradle.kts comment swept for `jphein/` references; spec docs left frozen as historical record.

## [0.5.21] — 2026-05-13

### Infrastructure
- **Repo moved from `realm-watch` to `techempower-org`** — second transfer of the day. realm-watch was originally framed around homelab theming; `techempower-org` is JP's company org and the more permanent home for storyvox and its product-line siblings. Eight repos transferred together: storyvox, storyvox-feeds, VoxSherpa-TTS, forageforall, techempower.org (the website), mempalace, palace-daemon, multipass-structural-memory-eval. realm-watch stays alive for future homelab projects.
- Owners / Maintainers / Contributors teams replicated on techempower-org with the same admin / maintain / push permissions; all eight repos team-bound. CODEOWNERS team mentions updated to `@techempower-org/*`. Branch protection on `main` re-applied (CODEOWNERS review + green CI + no force-push + no deletion + conversation resolution required).
- VoxSherpa-TTS JitPack coordinate: `com.github.techempower-org:VoxSherpa-TTS:v2.7.13` (was `com.github.realm-watch:...`, was `com.github.jphein:...`). Verified the new coordinate builds with `--refresh-dependencies` before flipping.
- SIGIL_REPO updated; CLAUDE.md memories swept of `realm-watch/storyvox` references.

## [0.5.20] — 2026-05-13

### Added
- **Audio-stream backend category** (#389, closes #373) —
  `ChapterContent` gains optional `audioUrl: String?`. When non-null
  the playback engine bypasses the TTS pipeline and routes the URL
  through a sibling Media3 ExoPlayer instance; when null (every
  existing backend) the TTS path is unchanged. Schema migration
  v6→v7 adds the `audioUrl` column so live-stream URLs persist
  across reboots. Pitch slider hides on live audio (Sonic
  pitch-shifting applies to engine-rendered PCM, not network audio).
- **KVMR community radio** (#389, closes #374) — first concrete
  entry in the audio-stream category. JP's local station; single
  live fiction whose one chapter (`Live`) carries the AAC stream
  URL from KVMR's public listen-live page. Defaults ON. Browse →
  KVMR → Live; lockscreen MediaSession surfaces "KVMR Community
  Radio" with transport controls.

### Changed
- **FictionDetail Follow button generalized** (#388, closes #382) —
  the hardcoded `sourceId == "royalroad"` check becomes
  `FictionSource.supportsFollow: Boolean` (default false), plumbed
  through `FictionSummary.supportsFollow` and
  `UiFiction.sourceSupportsFollow`. RoyalRoadSource opts in;
  future AO3 / GitHub-watch / Wikipedia-watchlist / etc backends
  opt in with one line of override.

### Infrastructure
- **Repo transferred from `jphein` to the `realm-watch` org** —
  storyvox + storyvox-feeds + VoxSherpa-TTS all moved. Public on
  the free tier. Branch protection on `main` requires CODEOWNERS
  review + green CI; no force-push, no deletion. Three teams
  (Owners / Maintainers / Contributors) wired with admin / maintain
  / push permissions. CODEOWNERS routes sensitive paths
  (`storyvox-debug.keystore`, `.github/workflows/`, `CLAUDE.md`) to
  Owners and build/release-affecting files to Maintainers. Org-level
  Projects v2 board "storyvox roadmap" with Priority + Area fields.
  VoxSherpa-TTS JitPack coordinate updated to
  `com.github.realm-watch:VoxSherpa-TTS:v2.7.13`.

## [0.5.19] — 2026-05-13

### Added
- **Three new fiction backends** landed in parallel:
  - **Archive of Our Own** (#385, closes #381) — fanfiction via per-tag
    Atom feeds + official EPUB downloads. Zero scraping. Six curated
    fandoms in v1 (Marvel/HP/SW/Original Work/Sherlock/Good Omens).
    Defaults OFF (Explicit-rated possibility).
  - **Standard Ebooks** (#386, closes #375) — curated typographically
    polished public-domain classics. Catalog via SE's public HTML
    listing (schema.org RDFa structured data), content via per-work
    EPUB. Pairs with Gutenberg as the "polished classics" companion.
  - **Wikipedia** (#387, closes #377) — first non-fiction long-form
    backend. Each article = one fiction, each top-level section = one
    chapter. Search via opensearch, Popular = Today's Featured Article
    + mostread cluster. Per-language host configurable in Settings.

### Changed
- **Sonic pitch-interpolation quality toggle** (#372, closes #193) —
  new Settings → Voice & Playback switch *"High-quality pitch
  interpolation"*, defaults ON. Cross-repo with VoxSherpa-TTS v2.7.13
  which parameterized `Sonic.setQuality` via static fields on both
  VoiceEngine and KokoroEngine.

## [0.5.18] — 2026-05-13

### Fixed
- **Gutenberg Browse tap no longer crashes** (#371) —
  `GutendexApi.{request, downloadEpub}` now wrap their OkHttp
  `execute()` calls in `withContext(Dispatchers.IO)`. `suspend`
  alone doesn't move work off the main thread; the previous
  implementation tripped StrictMode's `NetworkOnMainThreadException`
  on the first DNS lookup. Pattern now matches `:source-outline` /
  `:source-rss`.
- **Pick-a-voice picker** (#369) — surfaces three Piper quality
  tiers of Lessac (en_US) plus two of Cori (en_GB), not a mixed
  Cori/Lessac/Aoede grab-bag. Removes Aoede's misleading "1 MB"
  size (Kokoro `sizeBytes = 0` is correct — shared model — but
  rounded nonsensically). Strips stale ⭐ from Lessac/Ryan/Amy
  `displayName` so favorites can own the glyph unambiguously.

### Changed
- **Voices is now a first-class bottom-nav slot** (#370, closes
  #264 nav part) — replaces Settings in the bottom bar (last
  position, `RecordVoiceOver` icon). Settings moves to a gear
  IconButton in every main screen's top bar (Library, Browse,
  Follows, Playing, Voices). Voice-picking is a high-frequency
  activity for an audio-first app, not a set-once preference.
- First-launch default voice changes from `piper_cori_en_GB_high`
  (114 MB) to `piper_lessac_en_US_low` (63 MB) — the smallest of
  the new starter triplet. Users who want richer audio pick
  Medium or High in the picker before the gate dismisses.

## [0.5.17] — 2026-05-13

### Added
- **Follow on Royal Road button on FictionDetail** (#368, closes
  #211) — inline action bar gains a third button next to *Add to
  library* and *Listen*, visible only on RR-sourced fictions.
  Pushes the follow state to RR's account via the existing
  `RoyalRoadSource.setFollowed()` (CSRF + POST to
  `/fictions/setbookmark`). Anonymous tap routes to the same
  `AUTH_WEBVIEW` Browse and Settings already use. Closes the
  two-way sync loop — pull from `/my/follows` was already wired in
  v0.4.x.

### Changed
- `UiFiction` and `FictionSummary` gain `sourceId` and
  `followedRemotely` fields (defaulted to be backward-compatible
  with all existing construction sites).

## [0.5.16] — 2026-05-13

### Changed
- **RSS feed management moves to a Browse FAB** (#367, closes #247) —
  add / list / remove / suggested-feeds all live on a `+ Add feed`
  FAB-launched sheet from Browse → RSS now. The Settings page keeps
  only the on/off toggle (its subtitle points users at the new home).
  Same underlying repository API; only the home screen changed.

## [0.5.15] — 2026-05-13

### Added
- **Project Gutenberg backend** (#366, closes #237) — 70,000+
  public-domain books via Gutendex. New `:source-gutenberg` module:
  catalog browsing via the JSON API; add-to-library downloads each
  book's EPUB to `cacheDir/gutenberg/<id>.epub` and renders chapters
  through `:source-epub`'s parser. Most-legally-clean source in the
  storyvox roster — PG actively encourages programmatic access.
  Defaults to ON for fresh installs.

### Changed
- New `BrowseSourceKey.Gutenberg` chip in the picker; supports
  Popular / NewReleases / Search tabs. BestRated has no analogue on
  PG. No filter sheet in v1 — topic search through the Search tab
  covers the discovery cases.

## [0.5.14] — 2026-05-13

### Added
- **Royal Road soft sign-in gate on Browse listings** (#365, closes
  #241) — when the user is not signed in to RR, the Browse → RR
  Popular / NewReleases / BestRated / filter-active tabs render a
  brass sign-in CTA instead of firing an anonymous request. Search
  and Add-by-URL stay open anonymously. Authenticated traffic
  removes the "anonymous bot" framing — every listing fetch now
  carries a real RR session cookie. Closes #240 as superseded
  (#241's soft alternative chosen).

### Changed
- `BrowseViewModel` — three auth signals (gh sign-in, gh repo scope,
  rr sign-in) now bundle through a single `AuthSnapshot` flow so the
  outer controls combine stays under the 5-arg overload ceiling
  after gaining a third boolean.

## [0.5.13] — 2026-05-13

### Added
- **EPUB export from FictionDetail** (#364, closes #117) — overflow-menu
  "Export as EPUB" action. New `:source-epub-writer` module mirrors
  the reader/import that landed in #235: persisted rows assemble into
  a valid EPUB 3.0 zip at `cacheDir/exports/<sanitized-title>.epub` and
  hand off to the Android share-sheet through a scoped FileProvider
  (`xml/file_paths.xml` only exposes `exports/`, not the rest of
  cacheDir). `<dc:source>` metadata names the original backend
  (Royal Road, RSS, Outline, GitHub, EPUB).

### Changed
- `ChapterDao.allChapters(fictionId)` — new single-pass query returning
  every chapter row (including bodies) for export. Independent of the
  shelves/history v6 schema; no migration.

## [0.5.12] — 2026-05-13

### Added
- **InstantDB cloud sync foundation** (#360, #158-adjacent) — new
  `core-sync` module syncing library, follows, playback positions,
  bookmarks, pronunciation dictionary, and secrets through InstantDB.
  Magic-code sign-in screen, conflict policies per syncer, 24h tombstone
  TTL so re-adds propagate, PBKDF2 600k rounds (NIST 2024 / OWASP) for
  user-derived keys, format-v2 envelope. App cold-start initializes the
  sync graph.
- **Library shelves** (#362, closes #116) — predefined Reading / Read /
  Wishlist shelves with many-to-many membership. Chip-row filter above
  the library grid (visible on the All sub-tab), long-press a cover to
  open the manage-shelves bottom sheet. Empty state copy reads per
  shelf instead of the generic "library is empty".
- **Reading history sub-tab** (#363, closes #158) — Library now has
  All / Reading / History sub-tabs. History is a chronological feed of
  every chapter open, most-recent first, with relative-time labels
  ("2h ago"). Tapping a row opens the reader at that chapter without
  auto-starting audio. Forever retention.
- **Magical resume prompt on the Playing tab** (#361) — when the user
  has paused mid-chapter, opening Playing surfaces a brass-themed
  Library Nocturne prompt to resume from the saved offset.

### Changed
- `LibraryTab.Reading` coerces the chip filter to `OneShelf(Reading)`
  internally, so the same shelf-scoped Room flow drives both surfaces.
  Chip row is hidden on the Reading and History tabs (the tab is the
  filter / history is its own feed).
- Room database schema bumped to **v6**. Migration chain is `1→2→3→4→5→6`
  with all steps purely additive — no existing data touched. `v5` adds
  `fiction_shelf` (junction), `v6` adds `chapter_history` (one row per
  fiction+chapter, upsert on open).

### Fixed
- (Post-merge fix) `ChapterDao.allBookmarks()` newly-abstract member
  broke the test fixtures' two `FakeChapterDao` stubs; both now
  implement the override.

## [0.5.11] — 2026-05-12

### Fixed
- Bottom-tab taps lost under playback recomposition (#359) — dropped
  `popUpTo + saveState/restoreState` with mixed enter/exit transitions
  that committed the back-stack swap without rendering. Trade-off is
  lost tab-scroll-position memory.

## [0.5.07] — 2026-05-12

### Fixed
- RSS chapter reorder crash (#350) — atomic two-phase chapter upsert
  parks existing indexes to `+100_000` inside a `@Transaction` before
  upserting the fresh batch, preventing SQLite's immediate UNIQUE
  constraint check from firing mid-batch.

## Earlier — see `git log`

Releases v0.5.00 through v0.5.10 predate this changelog. The git log
captures their contents — every `release: vX.Y.ZZ` commit has a
substantive body. Notable milestones:

- **v0.5.00** (2026-05-10) — milestone release. UX wave (audit, research,
  build, grind), played indicators, nav/playback survival, settings
  shimmer, browse polish.
- **v0.5.07** (2026-05-12) — RSS reorder UNIQUE-constraint crash fix.
- **v0.5.10** (2026-05-12) — chapter bookmarks (#121) + self-hosted CI
  runner (#358) migrating off the capped jphein hosted-Actions minutes.
- **v0.5.11** (2026-05-12) — library nav fix while audio is playing.
