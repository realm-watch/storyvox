# Performance Modes — Design Spec

**Author:** Vesper-2 (retroactive spec writer, perf lane)
**Date:** 2026-05-08
**Status:** Retroactive — documents what shipped via #84 / #90 / #93 / #95 / #98 / #109 / #115. **No code change in this PR.** Mode C is the one outlier — scaffolded but not yet implemented; ships with Aurora's PCM cache PR-D.
**Branch:** `dream/vesper2/perf-modes-spec`
**Issues touched:**
- [#84](https://github.com/jphein/storyvox/issues/84) — Buffer slider (Mode D)
- [#90](https://github.com/jphein/storyvox/issues/90) / [#93](https://github.com/jphein/storyvox/issues/93) / [#109](https://github.com/jphein/storyvox/issues/109) / [#115](https://github.com/jphein/storyvox/issues/115) — Punctuation cadence (Mode E)
- [#95](https://github.com/jphein/storyvox/issues/95) — Buffer slider polish (Mode D)
- [#98](https://github.com/jphein/storyvox/issues/98) — Performance & Buffering section, toggles (Mode A, Mode B, scaffold for Mode C)
- [#86](https://github.com/jphein/storyvox/issues/86) — PCM cache (gates Mode C; see Aurelia's spec)

## Recommendation (TL;DR)

Five **Performance Modes** name the user-tunable surface for the perceived-quality / start-latency / memory / storage trade-offs that storyvox has to expose because Piper-high voices run at **0.285× realtime** on the target device (Tab A7 Lite, Helio P22T, 3 GB RAM). Without these modes, a high-quality voice is structurally unusable on this hardware: synthesis falls behind playback by ~700 ms per second of audio, the queue grace window expires, and the listener hears 8-19 second silences between sentences for the rest of the chapter (Aurelia, [`2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md)).

The five modes are independently toggleable knobs, **not exclusive radio choices**. Each has a scoped concern and a distinct code seam:

| Mode | Knob | Default | Range | Where it lives | Status |
|---|---|---|---|---|---|
| **A — Warm-up Wait** | toggle | ON | `Boolean` | UI-side gate at `PlaybackState → UiPlaybackState` | Shipped (#98) |
| **B — Catch-up Pause** | toggle | ON | `Boolean` | Consumer-thread pause/resume in `EnginePlayer.startPlaybackPipeline` | Shipped (#98 reframed PR #77) |
| **C — Full Pre-render** | toggle | OFF | `Boolean` | (Pending) — gates cache-render-then-play in `EnginePlayer.loadAndPlay` | **Not yet shipped.** Toggle scaffold deferred from #98; activates when Aurora's PCM cache PR-D lands. |
| **D — Buffer Headroom** | slider | 8 chunks | `2..1500` (recommended max 64) | `EngineStreamingSource.queueCapacity` constructor arg | Shipped (#84 / #95) |
| **E — Punctuation Cadence** | slider | 1.0× | `0.0..4.0×` (continuous, 0.05× resolution) | `EngineStreamingSource.punctuationPauseMultiplier` × `trailingPauseMs` table | Shipped (#90 → #93 → #109 → #115) |

These are the *escalating-strategies* surface: a user on a fast device with Kokoro never opens this section; a user on Tab A7 Lite with Piper-high turns A on, B on, eventually C on, dials D up, and tunes E to taste. The combinations are the design — see [Stacking semantics](#stacking-semantics).

## Why a formal spec, retroactively

1. **The five modes shipped piecewise.** PR #77 (Phoenix) added Mode B's pause-buffer-resume, PR #84 + #95 (Caspian) shipped Mode D, PRs #90 / #93 / #109 / #115 (Sable + Lark) iteratively widened Mode E from a 3-stop selector to a continuous slider. PR #98 (Bryn) created the Settings → Performance & Buffering section, formalised Modes A and B as named user-facing toggles, and reserved the Mode C slot. Each PR has its own design rationale, but the **interaction story** between the five modes — how they stack, what each costs, what defaults make sense — was never captured in one place.

2. **A future contributor needs to be able to add a sixth mode without re-deriving the framework.** Today, a contributor proposing "Mode F — Loudness Normalize" or "Mode G — Speech-DSP Bypass" has no shared language for "where does this live in the trade space, what does it stack against, what's the UI placement convention." Naming the modes A-E and writing the rules down makes the framework legible.

3. **Mode C is partly real (toggle slot reserved) and partly imaginary (no rendering yet).** The boundary between "shipped" and "shipped" is fuzzy without a doc to point to. JP needs to see Mode C's contract committed to before Aurora's PR-D lands, so the implementation has a target to satisfy. This spec defines that target.

4. **The Settings UI redesign ([`2026-05-08-settings-redesign-design.md`](2026-05-08-settings-redesign-design.md), Indigo) treats Performance & Buffering as a structural slot with a stable contract.** Without an artifact to point at, "what goes in P&B vs. Voice & Playback" is a per-PR judgment call. This spec gives Indigo's redesign — and its successors — a reference for "is this knob a Performance Mode or something else."

5. **Telemetry is upcoming.** Once defaults are known, we can measure whether they're right. A telemetry plan ([Telemetry plan](#telemetry-plan)) needs a stable ontology to log against; Mode A/B/C/D/E gives us that ontology.

## Non-goals

- **No retroactive design changes.** Every shipped mode behaves exactly as it did on `main` at this commit. The values, defaults, ranges, persistence keys, and code seams are documented as-is. A few "we'd do this differently today" notes are flagged inline as **Note (post-hoc)** but do not propose changes.
- **No new dependencies.** Spec only.
- **No tablet install / version bump.** Spec PR only, per Iron Rules.
- **No prescription for Mode C's exact UI copy or migration path.** That's Aurora's PR-D scope; this spec defines the contract slot only.
- **No discussion of lower-level engine knobs** (decoder choice, DSP routing, threading model). Those live in Phoenix's playback investigation lane and may eventually surface as Performance Modes, but today they're internal.

## Problem framing

### The numbers

Aurelia's baseline measurement on Tab A7 Lite (R83W80CAFZB), Piper-high "cori", Sky Pride Chapter 1, sentences 8-28 (Aurelia, [`2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md) §Problem):

| metric | value |
|---|---|
| audio synthesized | 45 520 ms |
| wall time spent generating | 159 820 ms |
| **realtime factor** | **0.285×** |
| inter-chunk audible gap (median) | 8 021 ms |
| inter-chunk audible gap (max) | 19 601 ms |

Piper-medium on the same device runs ≈ 0.7-0.9× rtf depending on sentence length; Kokoro speakers run ≈ 1.3-1.8× rtf after the one-time ~30 s session warm-up. **Only Kokoro consistently outpaces playback.** Piper-high is the canonical "high-quality voices play smoothly on slow devices" failure case; Piper-medium is the borderline case where smoothness depends heavily on sentence length variance.

### The structural shape of the problem

The producer/consumer playback pipeline ([`EnginePlayer.startPlaybackPipeline`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) lines 475–700) has a one-time grace at chapter start equal to the queue depth × per-sentence audio duration, plus the AudioTrack hardware buffer (~2-3 s on this device). Once that grace expires, every queue underrun is structural: synthesis is slower than playback, no in-pipeline knob can close the deficit. The user hears one of two things, depending on Mode B:

- **Mode B ON (PR #77, default):** the consumer pauses `AudioTrack` when `bufferHeadroomMs.value < 2_000ms` ([`EnginePlayer.kt:1074`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) `BUFFER_UNDERRUN_THRESHOLD_MS`) and surfaces an `isBuffering=true` UI flag. The listener hears a clean pause, sees the buffering spinner, then audio resumes when headroom recovers past `BUFFER_RESUME_THRESHOLD_MS = 4_000ms` (hysteresis to avoid play/pause thrash).
- **Mode B OFF:** the consumer drains through the underrun. `AudioTrack` empties, the listener hears dead air for the duration of the synthesis deficit, then audio resumes mid-sentence-boundary. No spinner.

Neither is good. **Both are better than the v0.4.26 silence-without-feedback failure mode** that triggered PR #77 in the first place. The deeper structural fix is **Mode C — Full Pre-render**: render to disk before playback, eliminate the synthesis bottleneck from the playback hot path entirely. That work is in flight in the PCM cache lane (Aurelia's PR-A through PR-H).

Performance Modes are the user-tunable handles on this trade space.

### The trade space

Three axes, every Mode lives somewhere on them:

| Axis | Costs | Benefits |
|---|---|---|
| **Start-up latency** | Higher = user waits longer before hearing audio | Lower = audio starts even on slow voices, but with silence/gaps |
| **Smoothness** | Higher = no underruns, no buffering UI, no dead air | Lower = audible underruns, less infrastructure |
| **Memory / storage** | Higher = more RAM for queue, disk for cache | Lower = small process footprint, no disk eviction churn |

Each Mode toggles or slides one axis at a known cost on the others:

```
                       smoother
                          ↑
                          │
        Mode C (off-disk) ●───→ unlocks "always smooth" once cache exists
                          │     (but big upfront wait + storage)
                          │
        Mode B (catch-up) ●───→ trade dead air for spinner
                          │     (default ON, low cost)
                          │
        Mode D (headroom) ●───→ wider grace window
                          │     (memory cost; LMK risk past tick)
                          │
                Mode E (cadence) ●───→ shorter inter-sentence pauses ≈ less synthesis deficit
                                  (perceptual cost: rushed reading)
                                  (default 1×; 0× actively papers over the deficit)
                          │
        Mode A (warm-up)  ●───→ start-immediately vs. wait-for-first-audio
                          │     (UX cost: silence at start vs. wait)
                          │
                          ↓
                    faster-to-start
```

A user can sit anywhere in this space. The Performance & Buffering section lets them.

## Mode-by-mode design

Each mode has the same documentation shape: name, semantic, default + range, persistence key, code seam, tests, telemetry hook (where applicable), notes.

### Mode A — Warm-up Wait

**Semantic.** When **ON** (default), the UI shows a "warming up" spinner and freezes the wall-time scrubber interpolation while the voice engine is loading and producing the first sentence's audio. When **OFF**, the UI behaves as if playback started immediately: the scrubber ticks from `t=0`, the play button looks "playing," but the listener hears silence until the first synthesized chunk is ready. Visual feedback in exchange for audible feedback.

**Default.** `true`. Preserves the v0.4.30 spinner-on-warmup behavior. The OFF mode is for users who'd rather see motion than a spinner on slower devices, especially on Kokoro's first-chapter-of-the-session 30 s session-warm-up.

**Range.** `Boolean`. No partial / timed variants — the mode is purely UI presentation, no engine state to interpolate.

**Persistence.** `pref_warmup_wait_v1` (boolean), DataStore. Default `true`. Versioned suffix `_v1` so a future ramp-style "show spinner only if warm-up exceeds N seconds" variant can land without colliding with persisted v1 values. ([`SettingsRepositoryUiImpl.kt:118`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt))

**Where it lives in code.**

| Layer | File:line | Role |
|---|---|---|
| Contract | [`core-data/.../playback/PlaybackModeConfig.kt`](../../../core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt) | `val warmupWait: Flow<Boolean>` + `suspend fun currentWarmupWait(): Boolean` |
| Persistence | [`SettingsRepositoryUiImpl.kt:118`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | `WARMUP_WAIT` key, default `true` |
| Persistence (writer) | [`SettingsRepositoryUiImpl.kt:249`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | `setWarmupWait(enabled: Boolean)` |
| UI cache | [`AppBindings.kt:409`](../../../app/src/main/kotlin/in/jphe/storyvox/di/AppBindings.kt) | `@Volatile cachedWarmupWait: Boolean = true` |
| Observer | [`AppBindings.kt:454-462`](../../../app/src/main/kotlin/in/jphe/storyvox/di/AppBindings.kt) | Mirrors `settings.warmupWait` into the volatile cache |
| **Gate point** | [`AppBindings.kt:637`](../../../app/src/main/kotlin/in/jphe/storyvox/di/AppBindings.kt) | `val warmingUp = rawWarmingUp && cachedWarmupWait` — the one-line gate that converts engine-side warm-up state into the UI-visible `isWarmingUp` flag |
| UI surface | [`SettingsScreen.kt:117-136`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt) | Toggle row in Performance & Buffering |

The gate is **synchronous** — it runs inside the `combine { ... s.toUi(nowMs) }` mapping that produces every `UiPlaybackState`. The volatile cache is the only way to read the user's preference without forcing the mapper to suspend.

`rawWarmingUp` (line 636) is `isPlaying && sentence == null` — i.e. "the user has hit play, the engine is loading or generating the first sentence, but no sentence range has been emitted yet." Mode A AND-gates that with the user's preference. When the preference is OFF and the engine is in the warm-up window, the UI reports `isWarmingUp=false` AND lets the wall-time interpolation tick from anchor (line 638), so the scrubber appears to advance even though no audio is playing.

**Tests.** `app/src/test/kotlin/in/jphe/storyvox/data/SettingsRepositoryModesTest.kt` covers default value, write/read round-trip, both-directions toggle, and DataStore re-emission shape. No engine-level test exists because the gate has no engine-side behavior — it's purely a UI projection. Visual verification on tablet is the acceptance test.

**Notes (post-hoc).**
- The OFF mode trades silent audio for visible motion, which is honest UX feedback only if the user *understands* what they're getting. The current copy does that ("Start playback immediately; accept silence at chapter start.") but a tooltip or first-toggle modal might help. Not implemented; flagged for future polish.
- Mode A has no telemetry hook today. We don't measure how often warm-up exceeds N seconds. See [Telemetry plan](#telemetry-plan).

### Mode B — Catch-up Pause

**Semantic.** When **ON** (default), the streaming pipeline pauses `AudioTrack` on mid-stream underrun and surfaces an `isBuffering=true` UI flag while the producer catches up. When **OFF**, the consumer thread drains through underruns: the listener hears dead air for the duration of the synthesis deficit, but never sees the buffering spinner. The underlying `EngineStreamingSource` is identical either way; only the consumer's pause/resume branches in `EnginePlayer.startPlaybackPipeline()` are gated.

**Default.** `true`. Preserves PR #77 (Phoenix) pause-buffer-resume behavior, which itself preserved v0.4.26's general approach but fixed the "spinning at URGENT_AUDIO during a paused write loop" regression that hung the app on slow voices.

**Range.** `Boolean`.

**Persistence.** `pref_catchup_pause_v1` (boolean), DataStore. Default `true`. ([`SettingsRepositoryUiImpl.kt:122`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt))

**Where it lives in code.**

| Layer | File:line | Role |
|---|---|---|
| Contract | [`PlaybackModeConfig.kt`](../../../core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt) | `val catchupPause: Flow<Boolean>` + `currentCatchupPause()` |
| Persistence | [`SettingsRepositoryUiImpl.kt:122,253,269`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | Key, writer, flow |
| Engine cache | [`EnginePlayer.kt:152-162`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `@Volatile cachedCatchupPause: Boolean = true` |
| Observer | [`EnginePlayer.kt:178-184`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `observeModeConfig` collects `modeConfig.catchupPause` |
| **Gate (resume)** | [`EnginePlayer.kt:537-545`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `if (cachedCatchupPause && paused && headroom >= RESUME) { track.play(); paused = false; isBuffering=false }` |
| **Gate (pause)** | [`EnginePlayer.kt:600-612`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `if (cachedCatchupPause && !paused && headroom < UNDERRUN) { track.pause(); paused = true; isBuffering=true }` |
| **Gate (park)** | [`EnginePlayer.kt:625-644`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | While `paused`, suspend the consumer on `bufferHeadroomMs.first { it >= RESUME }` to avoid the URGENT_AUDIO spin documented in PR #77 |
| UI surface | [`SettingsScreen.kt:138-157`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt) | Toggle row |

**Thresholds** (companion object, [`EnginePlayer.kt:1074,1080`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt)):

```kotlin
const val BUFFER_UNDERRUN_THRESHOLD_MS = 2_000L  // pause when headroom drops below
const val BUFFER_RESUME_THRESHOLD_MS   = 4_000L  // resume when headroom rises past
```

Hysteresis is load-bearing. Without it, the consumer thrashes pause/play on every chunk transition once the queue runs near-empty. 2s/4s was tuned empirically against Tab A7 Lite's ~2-3 s deep AudioTrack hardware buffer: if we paused the track only when the hardware ring was already empty, the listener would hear the silence first and the spinner second.

**Mode B is read on every consumer iteration.** The flag is volatile, the flip takes effect on the next loop step with no pipeline rebuild. This is intentional: a user toggling Mode B mid-chapter shouldn't tear down their AudioTrack.

**Tests.** Same `SettingsRepositoryModesTest.kt` covers persistence. Engine-level behavior is covered by Phoenix's PR #77 test suite (`EnginePlayerPauseBufferResumeTest` — verifies the pause/resume threshold logic, the URGENT_AUDIO non-spin, and the `isBuffering` UI flag transitions).

**Notes (post-hoc).**
- The 2s underrun threshold is chosen for Tab A7 Lite's hardware buffer depth. On a faster device with a shallower buffer, the threshold could be lower; on a slower device with a deeper buffer, higher. We don't currently adapt — see [Open questions](#open-questions-for-jp) #1.
- Mode B only addresses *steady-state* underruns. The first-sentence "warm-up" silence is Mode A's territory. The two modes don't interfere because Mode A's gate runs at the UI projection layer and Mode B's gate runs at the consumer layer; they observe different states.

### Mode C — Full Pre-render

**Status.** **Not yet shipped.** Toggle scaffold deferred from #98 because the implementation depends on Aurelia's PCM cache PR-D ([`2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md) §Implementation outline). This spec **commits the contract**; PR-D fills it.

**Semantic.** When **ON**, `EnginePlayer.loadAndPlay` will not enter the streaming path. Instead it dispatches a foreground render of the entire chapter to disk via `PcmRenderScheduler`, surfaces a "Pre-rendering chapter… (estimated 4 min)" UI state with a determinate progress bar, then opens a `CacheFileSource` once `PcmCache.isComplete(key)` and begins playback. Eliminates underruns at the cost of **upfront wait equal to or greater than the chapter's duration on slow voices** (Piper-high on Tab A7 Lite renders at 0.285× rtf — a 26-min chapter takes ~90 min wall time to fully pre-render).

When **OFF** (default), `EnginePlayer.loadAndPlay` uses the existing logic — cache-if-complete-else-streaming-fallback-with-tee-write — exactly as PR-E will land it.

**Default.** `false`. The wait cost is too high to enable by default; users opt in for chapters they're not in a hurry to start.

**Range.** `Boolean`. A future "auto-enable C if cache exists OR estimated render time < 30s" heuristic is plausible (see [Open questions](#open-questions-for-jp) #2) but not in the v1 contract.

**Persistence.** Pending. Suggested key: `pref_full_prerender_v1` (boolean), default `false`. Versioned to allow a future tri-state ("Off / Auto / Always") variant.

**Where it will live in code.**

| Layer | File | Role |
|---|---|---|
| Contract | `core-data/.../PlaybackModeConfig.kt` | Add `val fullPrerender: Flow<Boolean>` + `currentFullPrerender()` |
| Persistence | `SettingsRepositoryUiImpl.kt` | `FULL_PRERENDER` key + `setFullPrerender` writer + flow |
| Engine cache | `EnginePlayer.kt` | `@Volatile cachedFullPrerender: Boolean = false`, observer mirrors |
| **Gate point** | `EnginePlayer.kt` `loadAndPlay` | After voice-load, before `startPlaybackPipeline()`: if `cachedFullPrerender && !pcmCache.isComplete(key)`, dispatch a render-to-completion job, suspend the load with `isPrerendering=true` UI state until complete, then construct `CacheFileSource` |
| UI surface | `SettingsScreen.kt` Performance & Buffering | New toggle row (post-PR-D) |

The contract slot is reserved by precedent — Modes A and B established the volatile-cache + observer + gate pattern, and Mode C will follow the same shape. Implementation note: Mode C's gate is *deeper* in the call chain than A or B because it changes which `PcmSource` subtype is constructed, not just whether a flag is set on an existing pipeline. Aurora's PR-D will make this seam.

**Interactions with the cache.**
- Mode C ON + cache complete for `(chapterId, voiceId, speed, pitch, chunkerVersion)` → instant playback, no pre-render, identical to Mode C OFF + cache complete.
- Mode C ON + cache miss → foreground render-to-completion, `isPrerendering=true` with progress, then `CacheFileSource`.
- Mode C ON + voice swap mid-chapter → cache miss for the new voice key → re-render-to-completion. **This is the painful case.** A user who flips voices on a long chapter pays the full render wait again.
- Mode C OFF + cache miss (the today path) → `EngineStreamingSource` with tee-write to `PcmAppender`, cache populates as side-effect. Subject to underruns on slow voices, but the chapter starts playing immediately.

**UX shape (pending PR-D, but committed here).**
- Toggle copy ON: "Render the whole chapter before playback starts. Eliminates underruns at the cost of waiting up to chapter-length on slow voices."
- Toggle copy OFF: "Stream chapters immediately; render in background. May pause briefly on slow voices."
- Pre-rendering UI state: a determinate progress bar in the playback sheet with text "Pre-rendering chapter… 47%". Cancellable — tap cancel returns to streaming-fallback playback from `charOffset=0`.
- Behavior on app backgrounding mid-render: the render job uses `setForeground` so OS-doze doesn't interrupt it (per Aurelia's spec §Risks). User sees a foreground service notification.

**Why C is opt-in rather than default-ON-when-cache-empty.**

The render cost is too unpredictable. Piper-high on Tab A7 Lite is ≈3.5× chapter duration; Kokoro on the same device is ≈0.6× chapter duration; the same Kokoro on a faster device is ≈0.3× chapter duration. A user on Kokoro / fast device experiences Mode C as "barely-noticeable wait, then perfect playback" — net win. A user on Piper-high / Tab A7 Lite experiences Mode C as "give up the next 90 minutes for one chapter" — net loss for most listening sessions.

**Auto-enabling C** based on detected rtf is plausible (see [Open questions](#open-questions-for-jp) #3). The v1 contract is conservative: explicit opt-in.

**Notes (post-hoc).**
- The Mode C scaffold appears in the SettingsScreen #98 PR's review thread but was deferred per Bryn's comment "lands when Aurora's PR-D auto-populate logic is ready." The toggle stub doesn't appear in v0.4.32's UI; this spec is the reservation document.
- Mode C **does not replace** Mode B. A user with C off and B off still has the v0.4.26-equivalent failure mode (drain through underruns). The four orthogonal Boolean modes (A, B, C-when-shipped, plus the Mode E=0× edge case) compose; see [Stacking semantics](#stacking-semantics).

### Mode D — Buffer Headroom

**Semantic.** Pre-synthesis queue depth, in *sentence-chunks*. Bigger queue = more cushion against transient producer slowdowns, more memory used, more risk of Android's Low Memory Killer marking the app as background-killable. Smaller queue = lower memory floor, less cushion, more frequent under-runs.

The queue lives in [`EngineStreamingSource`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt) as a `LinkedBlockingQueue<Item>(queueCapacity)`. Producer back-pressures (blocks on `queue.put`) when full; consumer pulls from `queue.take()`.

**Default.** `8` chunks. Picked to match the pre-#84 hardcoded constant — backwards-compatible default, no behavior change for users who don't touch the slider.

**Range.** `2..1500` (mechanical bounds), with a *recommended max* tick at **64**.
- `BUFFER_MIN_CHUNKS = 2` ([`UiContracts.kt:413`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt)) — 1 in flight + 1 queued is the minimum that gives any back-pressure benefit.
- `BUFFER_DEFAULT_CHUNKS = 8` ([`UiContracts.kt:410`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt)) — the historical hardcoded value.
- `BUFFER_RECOMMENDED_MAX_CHUNKS = 64` ([`UiContracts.kt:422`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt)) — conservative ceiling below where we believe the queue is safe on a 3 GB device. ≈ 160 s of headroom × 2.5 s/sentence ≈ 7 MB PCM. Slider track turns amber past this.
- `BUFFER_MAX_CHUNKS = 1500` ([`UiContracts.kt:430`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt)) — mechanical upper bound. ≈ 165 MB PCM at 22050 Hz mono on Tab A7 Lite. **Past the LMK guess intentionally** — JP confirmed in #84 review that the slider is a probe (no hard cap), so users above the tick are voluntarily helping us measure where Android starts killing the app.
- `BUFFER_DANGER_MULTIPLIER = 4` ([`UiContracts.kt:433`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt)) — slider track turns red past `64 × 4 = 256` chunks.

The amber/red intensification is purely visual feedback; the engine doesn't behave differently. Past-tick copy in the slider explainer reads "Past the recommended max: Android may kill the app in the background. Help us find the exact limit by reporting what works." ([`SettingsScreen.kt:516-524`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt)).

**Persistence.** `pref_playback_buffer_chunks_v1` (int), DataStore. ([`SettingsRepositoryUiImpl.kt:113`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt)) Coerced to `[2..1500]` on read.

**Where it lives in code.**

| Layer | File:line | Role |
|---|---|---|
| Contract | [`core-data/.../PlaybackBufferConfig.kt`](../../../core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/) | `val playbackBufferChunks: Flow<Int>` + `currentBufferChunks()` |
| Persistence | [`SettingsRepositoryUiImpl.kt:113,243,259`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | Key, writer, flow |
| Engine cache | [`EnginePlayer.kt:140-149`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `@Volatile cachedBufferChunks: Int = 8` |
| Observer | [`EnginePlayer.kt:170-176`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `observeBufferConfig` collects `bufferConfig.playbackBufferChunks` |
| **Apply point** | [`EnginePlayer.kt:487-501`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) | `val queueCapacity = cachedBufferChunks.coerceIn(2, 1500); val source = EngineStreamingSource(... queueCapacity = queueCapacity)` |
| Source impl | [`EngineStreamingSource.kt:67,82`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt) | `queueCapacity` constructor arg → `LinkedBlockingQueue<Item>(queueCapacity)` |
| UI surface | [`SettingsScreen.kt:423-526`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt) `BufferSlider` | Slider + tick + amber/red copy |

**Critical gotcha: live changes don't apply mid-pipeline.** The bounded queue can't be resized after `EngineStreamingSource` is constructed. Mid-chapter slider drags update the volatile cache immediately, but they take effect on the **next pipeline construction** — which is the next chapter, the next seek, or the next voice/speed/pitch swap. This is documented at the apply point ([`EnginePlayer.kt:487-491`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt)):

```
// Snapshot the user's configured queue depth at pipeline-construction
// time. Mid-pipeline slider movements take effect on the next
// construction (next chapter / seek / voice swap); the bounded queue
// can't be resized live. Issue #84 — this is the LMK probe knob.
```

**Why D matters at all if Mode B exists.** Mode B (Catch-up Pause) only mitigates the listener experience of underrun — they hear a clean pause instead of dead air. Mode D pushes back the *frequency* of underrun by widening the grace window. On Piper-high / Tab A7 Lite at 0.285× rtf, a 64-chunk queue gives ≈ 160 s of grace before steady-state underrun begins; an 8-chunk queue gives ≈ 20 s. Once steady-state hits, Mode B takes over.

**Tests.** `SettingsRepositoryBufferTest.kt` (persistence + coercion), `SettingsViewModelBufferTest.kt` (UI binding + clamp behavior). No engine-level test at the apply point — covered by `EnginePlayerPauseBufferResumeTest` indirectly via queue depth fixtures.

**Notes (post-hoc).**
- The decision to expose 1500 chunks (≈ 165 MB) past the LMK-guess line is deliberate — JP wants the data. Users who run there and report results help us refine the recommended max. **This is documented in the PR #84 review thread.** Indigo's settings-redesign-spec proposes splitting the slider's experimental zone into an Advanced expander; that's not in v1 of either spec.
- The amber → red shift at `64 × 4 = 256` is "intuition-derived, not measured." Once telemetry lands ([Telemetry plan](#telemetry-plan)) we can refine the danger threshold against actual LMK kill rates.

### Mode E — Punctuation Cadence

**Semantic.** Continuous multiplier on the inter-sentence silence storyvox splices after each TTS sentence. The base pause table lives in `EngineStreamingSource.trailingPauseMs(...)` ([`EngineStreamingSource.kt:207-220`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt)):

| terminator | base pause |
|---|---|
| `.` `?` `!` `…` `...` | 350 ms |
| `;` `:` | 200 ms |
| `,` `—` `–` `-` | 120 ms |
| (anything else / clause-end) | 60 ms |

The user-facing multiplier scales every output. **Speed scaling is applied on top** so a 2× listener doesn't sit through long gaps even on `Long`:

```kotlin
val pauseMs = (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
```

([`EngineStreamingSource.kt:155-158`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt))

The semantic is "pause length the user wants to hear," not "raw silence buffer size." Setting multiplier=0× kills inter-sentence silence entirely — useful as a knob to *paper over* synthesis deficit, since each sentence's 350 ms sentence-end silence (≈ 15-20% of a 2 s sentence on Piper-high) becomes a synthesis budget the producer doesn't have to spend.

**Default.** `1.0×`. Audiobook-tuned baseline preserved across the #93 → #109 → #115 widening.

**Range.** `0.0..4.0` (continuous). The original #93 design was a 3-stop selector (Off / Normal / Long = 0× / 1× / 1.75×). #109 widened to a continuous slider because (a) users wanted slower-than-Long for high-drama narration and faster-than-Off was meaningless (0× already kills it entirely), and (b) the engine has always coerced to `[0..4]` internally — surfacing the full range was an additive UI change.

Tick labels at `0×` (Off), `1×` (Normal), `1.75×` (Long), `4×` (Theatrical) anchor the legacy stops + the new ceiling so users who liked "Long" can find it precisely.

**Persistence.** `pref_punctuation_pause_multiplier_v2` (float), DataStore. ([`SettingsRepositoryUiImpl.kt:108`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt))
- `_v2` versioning is real — `_v1` was the pre-#109 enum-string key (`pref_punctuation_pause` → `"Off"` / `"Normal"` / `"Long"`).
- A one-shot DataStore migration `PunctuationPauseEnumToMultiplierMigration` ([`SettingsRepositoryUiImpl.kt:67-93`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt)) maps existing v1 installs forward at first read: `"Off"` → 0×, `"Long"` → 1.75×, anything else (including `"Normal"`) → 1×. Idempotent.

**Where it lives in code.**

| Layer | File:line | Role |
|---|---|---|
| Constants | [`UiContracts.kt:453-460`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt) | `MIN=0f, MAX=4f, DEFAULT=1f, OFF=0f, NORMAL=1f, LONG=1.75f` |
| Persistence | [`SettingsRepositoryUiImpl.kt:108,236`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | Key, writer (coerces to `[0..4]`) |
| Migration | [`SettingsRepositoryUiImpl.kt:67-93`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) | Enum → Float migration |
| Forwarder | [`AppBindings.kt:425-430`](../../../app/src/main/kotlin/in/jphe/storyvox/di/AppBindings.kt) | Settings flow → `controller.setPunctuationPauseMultiplier(it)` (rebuilds pipeline if playing) |
| **Apply point** | [`EngineStreamingSource.kt:155-158`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt) | Multiplier × `trailingPauseMs` ÷ speed |
| UI surface | [`SettingsScreen.kt:577-619`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt) `PunctuationPauseSlider` | Continuous slider with tick labels |

**Live application.** Unlike Mode D (queue depth, baked at construction), Mode E's multiplier is a per-sentence calculation in the producer loop. A mid-chapter slider drag forwards through `controller.setPunctuationPauseMultiplier(it)` and rebuilds the pipeline if currently playing (because `EngineStreamingSource` takes the multiplier as a constructor arg). The rebuild interrupts audio for one queue refill cycle; this is the same shape as a speed change.

**Tests.**
- `SettingsRepositoryPunctuationPauseTest.kt` — persistence, coercion, default value.
- `SettingsViewModelPunctuationPauseTest.kt` — UI binding, slider range.
- Engine-level: covered by existing `EngineStreamingSource` tests for the silence-bytes calculation across the multiplier range.

**Notes (post-hoc).**
- The continuous slider's UX gain over the 3-stop selector is real but small. Most users settle on 1× or 1.75×. The 4× ceiling is for a small cohort of high-drama-narration listeners — it makes a 350 ms sentence-end pause into 1.4 s, which is theatrical but real audiobooks do this.
- 0× as a *performance* knob is genuinely useful but undersold in the current copy. A user on Piper-high might find that 0× cadence + 1× speed is more pleasant than 1× cadence + 1× speed, simply because the producer has more synthesis budget. Telemetry would help us understand this trade.
- Speed scaling on top of the multiplier is load-bearing — a user at 2× speed expects sentence boundaries to be perceptibly shorter, even if they've selected "Long." The `/speed.coerceAtLeast(0.5f)` clamp guards against a future sub-0.5× speed slider corrupting the pause math.

## Stacking semantics

The five modes are **independent toggles, not exclusive radio choices.** A user can turn any subset on or off, and the combinations are the design. This section documents the canonical combinations.

### The trade space, recovered

```
                        (storage)
                            ↑
                            │
        Mode C ON ●─────────●──── unlocks "always smooth"
                            │     after one upfront wait
                            │
                            │
        ┌───────────────────●───────────────────→ (memory)
        │                   │
        │                   ●  Mode D high (D >> default)
        │                   │
        │  Mode A OFF       │  Mode A ON
        │  Mode B OFF       │  Mode B ON
        │  Mode C OFF       │  Mode C OFF
        │  Mode D default   │  Mode D default
        │  Mode E lower     │  Mode E default
        │   (papering over) │
        │                   │
        ↓                   ↓
    (start latency)     (smoothness)
```

### Canonical recipes

#### A) The "smoothest possible playback, no compromise on quality" stack

**Modes:** A=ON, B=ON, **C=ON**, D=128 (or higher), E=1.0×

**Behavior.** Pre-renders every chapter to disk before playback starts (long upfront wait, ≈ 90 min for a 26-min chapter on Piper-high / Tab A7 Lite, ≈ 8 min on Kokoro / same device, ≈ 4 min on Kokoro / faster device). After pre-render, playback is gapless from cache — no underruns possible because no inference runs in the playback hot path. D and B are nominal defenses against the streaming-fallback path (cache miss / voice swap) — once the cache is populated, neither matters.

**Cost.** Up to chapter-duration upfront wait per chapter. Up to ~70 MB cache per Piper-high chapter; user-tunable cache quota with LRU eviction (Aurelia spec §Storage policy).

**Use case.** A user who doesn't mind sit-down-and-wait setup time (audiobook before bed, podcast while commuting). The bingeable-progression-fantasy-fan archetype: 30 chapters cached at install + add-to-library time, then perfect playback for a week.

#### B) The "fastest possible start, accept silence" stack

**Modes:** A=OFF, B=OFF, C=OFF, D=default (8), E=0.0×

**Behavior.** Tap Listen → scrubber starts ticking forward → audio starts when the engine catches up (silence until then). Mid-chapter, drains through underruns: listener hears dead air during synthesis deficits, no spinner. 0× cadence buys back the inter-sentence silence as synthesis budget, papering over a fraction of the deficit.

**Cost.** Audible silence at chapter start (multiple seconds on Kokoro's first session-warm-up, < 1 s on Piper subsequently). Mid-chapter dead air on slow voices. Lost cadence — sentences run together, less natural pacing.

**Use case.** A user on a fast device + a fast voice (Kokoro on a flagship) who finds spinners and pauses more disruptive than the rare missed dead air. Or a user who values "feels responsive" over "sounds clean."

#### C) The default stack (what ships today)

**Modes:** A=ON, B=ON, C=OFF, D=8, E=1.0×

**Behavior.** Standard PR #77 pause-buffer-resume on underrun, standard 8-chunk grace queue, audiobook-tuned cadence, warm-up spinner during engine load.

**Cost.** On Piper-high / Tab A7 Lite, the 8-chunk queue gives ≈ 20 s of grace before steady-state underrun, then the listener hits B's pause-buffer-resume cycle every 3-5 s. Watchable, but disruptive. On Kokoro / same device, the 30 s session warm-up shows the spinner once, then runs steady (≈ 1.3-1.8× rtf is comfortably ahead of playback).

**Use case.** Everyone, by default. Tunes from here.

#### D) The "stretch the queue, accept the LMK risk" stack

**Modes:** A=ON, B=ON, C=OFF, **D=512 (or higher)**, E=1.0×

**Behavior.** Wide grace window (~21 min of headroom at D=512 for Piper-high). Listener experiences the first 21 minutes of a chapter as gapless, then if the synthesis deficit is steady-state (Piper-high consistently behind playback), B's pause-buffer-resume kicks in at the back of that window. ~55 MB heap dedicated to the queue.

**Cost.** Memory pressure. On a 3 GB device, this is approaching where Android's LMK starts marking the app for kill. **A user above the recommended-max tick is voluntarily participating in the LMK probe.** Past `D=256` (the danger multiplier) the slider turns red.

**Use case.** A user who reads in the foreground (less LMK risk than backgrounded) and wants a longer chapter to play smoothly without the upfront cost of Mode C.

#### E) The "let it rush, hide the deficit" stack

**Modes:** A=ON or OFF (taste), B=ON, C=OFF, D=default, **E=0.0×**

**Behavior.** Cadence killed. Sentences run together. Synthesis budget per sentence is reduced by the 350 ms sentence-end silence, which on Piper-high is a meaningful chunk of the producer's cycle time. Reduces underrun frequency by 10-20% in pilot measurements.

**Cost.** Listening experience is rushed; lost the natural rhythm of audiobook narration. Acceptable for skim-listening, jarring for first-listen.

**Use case.** Re-listening to known content. Skim-mode reading.

### Stacking rules (formal)

1. **No mode forces another off.** All combinations are valid. There's no "Mode C requires Mode B off" or similar exclusivity.
2. **Modes apply at independent layers.** Mode A is UI-projection. Mode B is consumer-thread. Mode C is source-construction. Mode D is queue-construction. Mode E is per-sentence. Toggling one doesn't propagate state into the others.
3. **Some combinations are pointless but not forbidden.** Mode C ON + Mode D high is wasted memory on the rare cache-miss path; Mode C ON + Mode B ON is wasted gating on the cache-hit path. We don't enforce — the orthogonality is valuable to keep.
4. **Defaults are conservative for the default device.** A=ON, B=ON, C=OFF, D=8, E=1× preserves the v0.4.30 listening experience on a midrange device. A user who wants different behavior tunes from there.

## Target latencies

Measured on Tab A7 Lite (R83W80CAFZB), with the default chapter (Sky Pride Chapter 1, ~26 min total). Numbers are wall-clock time from "user taps Listen" to "first audio audible" (start latency) and "audible glitches per minute" (smoothness). Citations to Aurelia's baseline ([`2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md) §Problem) where relevant.

| Voice | Mode stack | Start latency | Smoothness |
|---|---|---|---|
| **Piper-high cori** | default (A=ON, B=ON, C=OFF, D=8, E=1×) | 25-45 s (engine load + first sentence at 0.285× rtf) | 1 buffering pause every 3-5 s after grace expires (~20 s in) |
| Piper-high cori | A=ON, B=ON, C=ON (cache populated) | < 2 s | Gapless |
| Piper-high cori | A=ON, B=ON, C=OFF, **D=128** | 25-45 s (same as default) | Gapless for ~5 min, then B kicks in |
| Piper-high cori | A=OFF, B=OFF (drain-through) | "Immediate" UI feedback, ~25 s until first audio | Multi-second dead air mid-chapter |
| **Piper-medium** | default | 5-10 s | ≈ 1 buffering pause every 30 s (sentence-length-dependent) |
| Piper-medium | C=ON (cache populated) | < 2 s | Gapless |
| **Kokoro (any speaker)** | default, *first* chapter of session | 30-45 s (one-time session warm-up) | Gapless after warm-up |
| Kokoro | default, *subsequent* chapter of session | 2-5 s | Gapless |
| Kokoro | C=ON (cache populated) | < 2 s | Gapless |

### Reading the numbers

- **The 25-45 s start window for Piper-high default** is the killer UX problem. The voice engine load is ~1-2 s; the rest is "first sentence's 9-13 s of audio synthesizing at 0.285× rtf." Mode C with a populated cache flattens this to filesystem I/O.
- **The Kokoro session warm-up** (30+ s, one-time per app process) is what Mode A's OFF mode is designed for. A user who'd rather see the scrubber move than a spinner during that window flips A off and accepts silence.
- **Piper-medium is the "borderline" voice.** Its rtf hovers around 0.7-0.9× depending on sentence length. Long sentences underrun; short ones don't. Mode B's pause-buffer-resume handles this gracefully, and Mode D doesn't help much because the deficit is intermittent.
- **B=OFF "drain-through"** is the failure mode users complain about most. The "dead air mid-chapter" experience. Default ON exists to prevent this.

### What's missing

- **No measurements with Mode E < 1×.** Pilot data suggests 0× cadence reduces underrun frequency by 10-20% on Piper-high; needs formal measurement.
- **No measurements at extreme Mode D values.** The recommended-max tick (64) is intuition-derived. Telemetry on actual LMK kill rates at D=128, 256, 512, 1024 would let us refine.
- **No multi-device matrix.** All numbers above are Tab A7 Lite. A flagship-class device flattens every row to "fast." A 2 GB device probably collapses Mode D's safe range.

## Telemetry plan

**Status: not yet implemented.** This section is the contract for what telemetry should capture once the user-facing toggle ships. Suggested home: a `telemetry-perf-modes.md` follow-up spec from the perf lane, owner TBD.

### What to log (anonymously, opt-in)

For each chapter playback session:

| Event | Payload | Rationale |
|---|---|---|
| `playback_start` | `voiceId`, `voiceFamily` (piper/kokoro), `voiceRtf` (measured first-sentence rtf), modes-snapshot `(A, B, C, D, E)`, `hasCacheHit: Boolean` | Session-level baseline |
| `warmup_observed_ms` | duration from `loadAndPlay` to first `sentenceRange` emission | Mode A tuning — is the default (ON) right? When is the warm-up window long enough that users want to skip it? |
| `underrun_event` | `headroomMsAtPause`, `pauseDurationMs`, `sentenceIndex` | Mode B tuning — how often does B fire, for how long? Mode D tuning — does increasing D push these later in the chapter? |
| `headroom_floor_ms` | min `bufferHeadroomMs.value` observed during the session | Mode D tuning — how close did the user get to underrun even when one didn't fire? |
| `prerender_complete` | `chapterDurationMs`, `renderWallTimeMs`, `voiceRtf` | Mode C tuning (post-PR-D) — is the upfront wait worth it on this voice/device? |
| `mode_toggle` | which mode, old value, new value | Discoverability — are users finding the modes? |
| `device_class` | RAM, SoC family, screen DP | Multi-device matrix recovery |

Privacy: opt-in with a clear toggle in Settings → Account or Settings → About. **No chapter content, no fiction IDs, no story metadata.** Voice family + RAM + SoC family + mode states + timing data is the entire payload. Aggregate-only on the receiving end.

### What we'd learn

1. **Default stack validation.** Are the defaults right? If telemetry shows `underrun_event` firing in 80% of Piper-high sessions, we should reconsider whether `D=8` is the right default for Piper-high specifically. (Adaptive defaults — see [Open questions](#open-questions-for-jp) #1.)
2. **Mode C adoption.** Once C ships, is anyone using it? On which voices? Does the upfront wait correlate with session abandonment, or with longer overall sessions?
3. **Headroom floor distribution.** Is the recommended-max tick at 64 the right line, or should it be 32, or 128?
4. **Toggle discovery.** Are users finding the Performance & Buffering section? If `mode_toggle` events are rare, we know the UI is buried; if they're common but always-back-to-default, we know the modes themselves aren't intuitive.
5. **Cache hit rate.** Once Mode C lands, `hasCacheHit` distribution tells us whether the auto-pre-render-on-add policy (Aurelia §Trigger points) is working.

### Settings-touched-per-session

A useful soft metric: how many distinct settings the user adjusts per session. Low number = sticky defaults (good); high number = users hunting for a working combination (bad — defaults are wrong, or framing is unclear).

### What we explicitly don't log

- Chapter content / fiction IDs / source URLs.
- User accounts, IDs, or anything cross-session-correlatable.
- Free-text fields. Telemetry payloads are structured + bounded.
- Exact device IDs. Device class is bucketed (RAM tier × SoC family).

## UX surface

Settings → **"Performance & buffering"** section, per Indigo's redesign spec ([`2026-05-08-settings-redesign-design.md`](2026-05-08-settings-redesign-design.md) §3 Performance & Buffering). Today this section lives in `SettingsScreen.kt` lines 100-176 as a flat-scroll segment with a `SectionHeader` + italic explainer + four rows; post-Indigo's redesign it becomes a `SettingsGroupCard` with the same five row composables.

### Row order (today, top-to-bottom)

1. Section header: "Performance & buffering"
2. Italic explainer: "Settings that trade upfront wait + memory for smoother playback. Useful on slower devices."
3. **Mode A — Warm-up Wait** (`SettingsSwitchRow`) — toggle + dynamic subtitle reflecting the current state's behavior
4. **Mode B — Catch-up Pause** (`SettingsSwitchRow`) — toggle + dynamic subtitle
5. *(Future, post-PR-D)* **Mode C — Full Pre-render** (`SettingsSwitchRow`) — toggle + dynamic subtitle
6. **Mode D — Buffer Headroom** (custom `BufferSlider` for now; will become `SettingsSliderBlock` post-redesign) — slider + tick + multi-paragraph caption + amber/red past-tick state
7. **Mode E — Punctuation Cadence** (custom `PunctuationPauseSlider`; will become `SettingsSliderBlock` post-redesign) — slider with `0×` / `1×` / `1.75×` / `4×` tick labels

### Order rationale

Cheapest to most-exploratory. Toggles first (low cognitive load, instant flip-back). Slider with high LMK risk (D, with experimental zone) before slider that's purely perceptual (E). Mode A before B because A is encountered earlier in a session (warm-up runs first). Mode C, when it ships, slots between B and D because it's the engine-level "structural fix" — once C is on, B and D become moot.

### Section copy

- **Header.** "Performance & buffering" — verbatim from Bryn's #98.
- **Section explainer.** "Settings that trade upfront wait + memory for smoother playback. Useful on slower devices." — sets the trade-space framing without naming the device.
- **Per-row dynamic subtitles.** Each toggle row's subtitle updates based on the current state, e.g. Mode A ON: "Wait for the voice to warm up before playback starts." Mode A OFF: "Start playback immediately; accept silence at chapter start." This is a non-default choice (most settings rows have a static subtitle) — the trade-off is the design here, so the subtitle is always the trade-off.
- **Slider readouts.** Mode D shows `Buffer: 8 chunks (~20s, ~2 MB)` with a `Recommended max: 64` indicator. Mode E shows `Pause after punctuation: 1.00×`. Both readouts include enough context for the user to translate the abstract knob into a concrete user-experience term.

### Future placement

Per Indigo's redesign:
- The section becomes a `SettingsGroupCard` (paper-cream / warm-dark `surfaceContainerHigh` + `shapes.large`).
- The buffer slider's experimental zone (past the recommended-max tick) optionally moves into an `AdvancedExpander` row in v2 of the redesign — deferred until the splitting becomes valuable. ([`2026-05-08-settings-redesign-design.md`](2026-05-08-settings-redesign-design.md) §Buffer slider integration)
- Mode C's row reserves the position **after Mode B, before Mode D**, per Aurora's PR-D.

## Cross-spec wiring

This spec sits at the center of three other in-flight specs:

### Aurelia — PCM cache ([`2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md))

- Mode C **is** Aurelia's PR-D auto-populate logic, surfaced as a user-facing toggle. Aurelia's spec §Implementation outline lists PR-D as "EngineStreamingSource tee writer" — that's the cache-population infrastructure. PR-E adds the cache-hit path. Mode C's toggle gates between (a) wait for full pre-render before playback (`PR-F + setForeground` foreground render) vs. (b) play immediately, populate cache as side-effect (the today path post-PR-D).
- Aurelia's `PcmCacheKey(chapterId, voiceId, speed, pitch, chunkerVersion)` is the cache identity Mode C consults via `pcmCache.isComplete(key)`.
- Mode C's "Pre-rendering chapter… 47%" UI state is a new `UiPlaybackState` field that lands with PR-D + this Mode C toggle simultaneously.

### Indigo — Settings UI redesign ([`2026-05-08-settings-redesign-design.md`](2026-05-08-settings-redesign-design.md))

- Indigo's redesign creates the **structural slot** ("Performance & Buffering") that this spec's modes fill. The redesign and this spec are independent — Indigo's PR can land before Mode C ships, or after — but both eventually live in the same section.
- The slot's row composable vocabulary (`SettingsSwitchRow`, `SettingsSliderBlock`) is what Modes A, B, C, D, E use post-redesign. Today they're hand-rolled (Mode D's `BufferSlider`, Mode E's `PunctuationPauseSlider`); post-redesign they consolidate to `SettingsSliderBlock` with caption slot.
- Indigo's `AdvancedExpander` row is the future home for Mode D's experimental zone. Not in v1 of either spec.

### Bryn — Performance & Buffering implementation ([PR #98](https://github.com/jphein/storyvox/pull/98))

- Bryn's PR #98 is the implementation that shipped Modes A and B as named toggles + reserved Mode C's slot. This spec is its retroactive design doc.
- Bryn coordinated with Indigo on the section header text and intra-section row order. Both are inherited here.

### Phoenix — Playback investigation lane ([PR #77](https://github.com/jphein/storyvox/pull/77) + follow-ups)

- Phoenix's PR #77 introduced the pause-buffer-resume mechanic that is now Mode B. The "Phoenix-fixed code" is the URGENT_AUDIO non-spin park-on-headroom branch ([`EnginePlayer.kt:625-644`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt)).
- Future Phoenix work (decoder choice, DSP routing) may surface as additional Performance Modes — those would extend this spec rather than supersede it.

### Caspian — Buffer slider ([PR #84](https://github.com/jphein/storyvox/pull/84) / [PR #95](https://github.com/jphein/storyvox/pull/95))

- Caspian's PRs shipped Mode D as a probe — the past-tick experimental zone was JP's explicit design ask. The 1500-chunk mechanical max + amber/red intensification are Caspian's design.

### Sable / Lark — Punctuation cadence (#90 / #93 / #109 / #115)

- Sable's #90 + Lark's #93 shipped Mode E as a 3-stop selector. Lark's #109 widened to a continuous slider; Lark's #115 is the polish PR (tick label spacing, readout precision). The DataStore migration from enum-string to Float lives in Lark's #109.

## Compatibility with future work

### A sixth mode

If a future contributor proposes Mode F (e.g., "Loudness Normalize"), the framework here suggests:
- Pick a one-axis trade-off (perceptual quality vs. consistency, smoothness vs. memory, etc.).
- Pick a code seam: UI projection (like A), consumer-thread (like B), source-construction (like C), queue-construction (like D), or per-sentence (like E).
- Document defaults conservatively; expose the experimental range with visual feedback (like D's amber/red).
- Land it in the Performance & Buffering section with a `SettingsSwitchRow` or `SettingsSliderBlock`.
- Add to telemetry's mode-snapshot tuple.

### Adapting defaults to device class

Currently the defaults are static (A=ON, B=ON, C=OFF, D=8, E=1×) regardless of device. Once telemetry lands, we may want device-class-aware defaults: a flagship-class device with Kokoro could default to Mode C ON + D=4 (smaller queue, no harm); a budget-class device with Piper-high could default to D=32 + E=0.5×.

This is **not** in the v1 spec — but the data model supports it. Adding `defaults_for_device_class` to the DataStore migration path is straightforward; the modes themselves don't change shape.

### VoxSherpa knob integration (Thalia's research)

Thalia's VoxSherpa research will produce a spec proposing new voice-quality knobs (loudness normalization, breath pause, pitch envelope). Some of those will be **Performance Modes** (engine-level trade-offs) and some will be **Voice & Playback** knobs (per-voice perceptual tuning). The classification rule from Indigo's redesign: engine-level / experimental → P&B; voice-shaping → V&P. Mode F-G-H land here; voice quality knobs land in Indigo's V&P card.

## Acceptance criteria (retroactive — already met)

This spec documents what shipped. The acceptance criteria reflect the v0.4.32 reality:

- [x] Mode A toggle persists; default `true`; OFF skips the warm-up spinner.
- [x] Mode B toggle persists; default `true`; OFF drains through underruns without buffering UI.
- [x] Mode D slider persists; default 8; range 2-1500; recommended-max tick at 64; amber/red past tick.
- [x] Mode E slider persists; default 1.0×; range 0.0-4.0×; tick labels at 0×, 1×, 1.75×, 4×; one-shot enum migration.
- [ ] Mode C toggle scaffolded but not exposed in UI; ships with Aurora's PR-D.
- [x] All five modes are independent (no enforced exclusivity).
- [x] All five modes' UI surfaces live in Settings → Performance & buffering section.
- [x] Per-mode tests (`SettingsRepositoryModesTest`, `SettingsRepositoryBufferTest`, `SettingsRepositoryPunctuationPauseTest`, ViewModels) pass.

## Open questions for JP

1. **Adaptive defaults by device class.** Should defaults flex with detected RAM / SoC? E.g., a 4 GB device defaults to D=16, a 6 GB device defaults to D=32. Today defaults are static. Pro: better OOTB experience for users who don't tune. Con: device-detection is brittle and the gain is small until telemetry validates the tier table. **Recommend defer until telemetry-1 lands.**

2. **Auto-enable Mode C on certain voices.** When Aurora's PR-D ships, should Mode C silently auto-enable on (a) the first chapter from a fiction added to library, (b) chapters where measured rtf < 0.5×, or (c) never (always opt-in)? The "auto-enable on add-to-library" path matches Aurelia's spec §UX-shape "schedules render of chapters 1-3" — except as a mode default rather than an unconditional behavior. **Recommend (c) — explicit opt-in for v1**, then re-evaluate with telemetry.

3. **D's recommended-max tick: should it adapt by RAM?** Today the tick is fixed at 64. On a 2 GB device that's still risky; on a 6 GB device that's conservative. Auto-flexing the tick based on `Runtime.getRuntime().totalMemory()` is straightforward but introduces device-specific UI that's hard to communicate. **Recommend defer until LMK-probe data shows the variance is real.**

4. **Mode C UI copy.** When Mode C ships, the toggle copy + the pre-render progress UI are user-facing. Suggested copy is in [§Mode C](#mode-c--full-pre-render) but should match Indigo's tone-of-voice for the surrounding section. **JP pick from drafts when PR-D opens.**

5. **Telemetry opt-in surface.** The telemetry plan above assumes a Settings → Account or Settings → About toggle. Indigo's redesign doesn't allocate a slot for it. **Need a placement decision** before the perf-telemetry-1 lane starts.

6. **Mode E's 0× as a perf knob.** Currently 0× is documented as "kill cadence." It's also a 10-20% underrun-frequency reducer on slow voices. Should we surface that explicitly in the slider's caption? Pro: users on slow voices get a no-cost smoothness gain by accepting flatter cadence. Con: framing 0× as "performance trick" undersells the artistic aspect of cadence selection. **Recommend keep current copy; note the behavior in this spec for tuning awareness.**

7. **Mode B threshold tunability.** `BUFFER_UNDERRUN_THRESHOLD_MS = 2_000` and `BUFFER_RESUME_THRESHOLD_MS = 4_000` are currently hardcoded. Are these worth surfacing as advanced sliders? Pro: power users could tune for their device's hardware buffer depth. Con: explodes the surface area; the hysteresis math is subtle and bad values cause thrashing. **Recommend keep hardcoded; revisit if device-class telemetry shows wide variance.**

## References

### Internal specs
- Aurelia, *Chapter PCM Cache — Design Spec*. [`docs/superpowers/specs/2026-05-07-pcm-cache-design.md`](2026-05-07-pcm-cache-design.md). The structural fix that unlocks Mode C.
- Indigo, *Settings UI Redesign — Design Spec*. [`docs/superpowers/specs/2026-05-08-settings-redesign-design.md`](2026-05-08-settings-redesign-design.md). The structural slot the modes live in.
- *Storyvox Architecture (canonical).* [`docs/superpowers/specs/2026-05-05-storyvox-design.md`](2026-05-05-storyvox-design.md).

### Code seams (anchors)
- [`core-data/.../playback/PlaybackModeConfig.kt`](../../../core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/PlaybackModeConfig.kt) — Mode A + B contract
- [`core-data/.../playback/PlaybackBufferConfig.kt`](../../../core-data/src/main/kotlin/in/jphe/storyvox/data/repository/playback/) — Mode D contract
- [`app/.../SettingsRepositoryUiImpl.kt`](../../../app/src/main/kotlin/in/jphe/storyvox/data/SettingsRepositoryUiImpl.kt) — DataStore keys + writers + flows + migration
- [`app/.../di/AppBindings.kt`](../../../app/src/main/kotlin/in/jphe/storyvox/di/AppBindings.kt) — Mode A gate in the UI projection layer
- [`core-playback/.../EnginePlayer.kt`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/EnginePlayer.kt) — Mode B and D gates in the consumer thread / pipeline construction
- [`core-playback/.../source/EngineStreamingSource.kt`](../../../core-playback/src/main/kotlin/in/jphe/storyvox/playback/tts/source/EngineStreamingSource.kt) — Mode D queue + Mode E multiplier application
- [`feature/.../UiContracts.kt`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/api/UiContracts.kt) — All constants (buffer min/max, punctuation min/max, defaults)
- [`feature/.../settings/SettingsScreen.kt`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt) — Performance & Buffering UI
- [`feature/.../settings/SettingsViewModel.kt`](../../../feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModel.kt) — UI binding

### Tests
- `app/src/test/kotlin/in/jphe/storyvox/data/SettingsRepositoryModesTest.kt` — Modes A + B persistence
- `app/src/test/kotlin/in/jphe/storyvox/data/SettingsRepositoryBufferTest.kt` — Mode D persistence + coercion
- `app/src/test/kotlin/in/jphe/storyvox/data/SettingsRepositoryPunctuationPauseTest.kt` — Mode E persistence + migration
- `feature/src/test/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModelModesTest.kt` — Modes A + B UI bindings
- `feature/src/test/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModelBufferTest.kt` — Mode D UI binding
- `feature/src/test/kotlin/in/jphe/storyvox/feature/settings/SettingsViewModelPunctuationPauseTest.kt` — Mode E UI binding

### Pull requests
- [PR #77](https://github.com/jphein/storyvox/pull/77) — Phoenix: pause-buffer-resume + URGENT_AUDIO park-on-headroom (Mode B foundation)
- [PR #84](https://github.com/jphein/storyvox/pull/84) / [PR #95](https://github.com/jphein/storyvox/pull/95) — Caspian: Mode D buffer slider + amber/red past-tick + LMK probe range
- [PR #90](https://github.com/jphein/storyvox/pull/90) / [PR #93](https://github.com/jphein/storyvox/pull/93) — Sable / Lark: Mode E original 3-stop selector
- [PR #98](https://github.com/jphein/storyvox/pull/98) — Bryn: Performance & Buffering section + Modes A and B as named toggles + Mode C scaffold (deferred)
- [PR #109](https://github.com/jphein/storyvox/pull/109) — Lark: Mode E enum-to-continuous slider + DataStore migration
- [PR #115](https://github.com/jphein/storyvox/pull/115) — Lark: Mode E tick polish

### External
- [Material 3 Slider tracks + tinting](https://m3.material.io/components/sliders/specs) (D + E slider styling)
- [Android `LinkedBlockingQueue` semantics](https://developer.android.com/reference/java/util/concurrent/LinkedBlockingQueue) (D back-pressure)
- [Android `LowMemoryKiller` thresholds](https://source.android.com/docs/core/perf/lmkd) (D recommended-max rationale)
- [DataStore Preferences migration](https://developer.android.com/topic/libraries/architecture/datastore#datastore-preferences-migration) (E enum→float migration)
