# VoxSherpa-TTS Knob Research — Spec

**Author:** Thalia (research lane)
**Date:** 2026-05-08
**Status:** Research output, awaiting JP triage
**Branch:** `dream/thalia/voxsherpa-research`
**Related:** none — pure cataloguing for future implementation

## Mission

Catalogue every config knob the VoxSherpa-TTS engine surface (and its
sherpa-onnx underpinning) exposes that storyvox could plausibly expose
to the user. Output a ranked, costed list so JP can pick which ones to
ship; future agents implement.

**Spec only — no implementation.**

## Inventory baseline (what's already exposed)

| Setting | UI | Range | Engine field |
|---|---|---|---|
| Voice picker | Library screen | enum | model swap (loadModel) |
| Default speed | Slider | 0.5×..3.0× | `OfflineTts.generate(speed=)` |
| Default pitch | Slider | 0.85×..1.15× | `Sonic.setPitch()` post-process |
| Punctuation pause | 3-button (Off/Normal/Long) | 0×/1×/2× | storyvox-side silence splice (NOT engine) |
| Audio buffer chunks | Slider | 2..1500 | producer queue depth (NOT engine) |
| Theme override | 3-button | enum | UI |
| Wi-Fi only download | Switch | bool | downloads |
| Poll interval | Slider | 1..24h | downloads |

The TTS-specific section is **three sliders + one selector**. Everything
else in this spec is a candidate to widen that.

## Research method

1. Read `engine-lib/build.gradle` in jphein/VoxSherpa-TTS (the JitPack
   coordinate `com.github.jphein:VoxSherpa-TTS:v2.7.3` resolves here).
2. Read the six Java source files engine-lib re-exposes:
   `KokoroEngine`, `VoiceEngine`, `Sonic`, `AudioEmotionHelper`,
   `KokoroVoiceHelper`, `GenerationParams`.
3. `javap -p` the cached `sherpa-onnx-1.12.26` AAR's `classes.jar` for
   the actual Kotlin config classes (`OfflineTts*Config`).
4. Read upstream `k2-fsa/sherpa-onnx` C++ headers for default values
   and parameter semantics:
   - `offline-tts-config.h`
   - `offline-tts-model-config.h`
   - `offline-tts-vits-model-config.h`
   - `offline-tts-kokoro-model-config.h`
   - `offline-tts-matcha-model-config.h`
   - `offline-tts-kitten-model-config.h`
5. Grep storyvox `core-playback/` for actual VoxSherpa method calls.

Detailed findings in
`scratch/loose-ends-round2-2026-05-08/thalia-voxsherpa/research-notes.md`.

## The plumbing reality

**Critical constraint:** most "interesting" sherpa-onnx model config
fields (`noise_scale`, `noise_scale_w`, `length_scale`, `silence_scale`,
`max_num_sentences`, `lexicon`, `dict_dir`, `lang`, `rule_fsts`,
`rule_fars`, `num_threads`, `provider`) are configured **inside**
VoxSherpa's `_createTtsWithFallback()` / `createTtsWithFallback()`
methods. There are no setter methods on the `VoiceEngine` /
`KokoroEngine` singletons that storyvox can call to retune them. The
values are baked into `loadModel()` and only changeable by destroying +
re-loading the model with a new internal config.

This means each candidate knob below has one of three plumbing paths:

- **(A) Already callable** — surface is on `VoiceEngine`/`KokoroEngine`
  or on `Sonic` and storyvox just isn't using it. Trivial wiring.
- **(B) VoxSherpa fork** — needs new public setters on
  `VoiceEngine`/`KokoroEngine` that accept the param and rebuild the
  model. Upstream PR to jphein/VoxSherpa-TTS, JitPack tag bump,
  storyvox dep bump. Medium lift per knob, amortized if we batch.
- **(C) Sherpa-onnx direct** — bypass VoxSherpa, build our own thin
  Kotlin wrapper around `OfflineTts` (we already pull
  `sherpa-onnx:1.12.26` directly). Big lift one-time, then every knob
  is path-(A) free. Sets us up to drop VoxSherpa entirely if jphein's
  fork ever stalls.

The C-path option is worth flagging because if we land **two or more**
B-path knobs, the C-path becomes cheaper than the sum of B-paths.

## Engine-side knob catalogue

### P0 — Ship next round

#### 1. Pitch range — widen the slider

- **Engine field:** `Sonic.setPitch(float)`
- **Type:** Float slider (already exists, just widen)
- **Default + range:** Default 1.0×; current UI band 0.85..1.15; Sonic
  supports ~0.5..2.0 cleanly.
- **What it does:** Shifts pitch up/down without changing speed. Lower
  pitch = deeper voice; higher pitch = chipmunk territory.
- **Why expose:** A 30% range (0.85..1.15) is conservative. Listeners
  who already pitched their default voice down 15% to make a young-male
  model sound right have nowhere left to go for "narrator-baritone."
  Even widening to 0.7..1.3 doubles the available tuning room and
  stays within Sonic's quality envelope.
- **Priority:** P0
- **Cost to implement:** S — change one constant in
  `SettingsScreen.kt:72` and the in-context pitch slider. Already
  threaded; just numbers.
- **Plumbing path:** (A)
- **Risk:** Below ~0.7 pitch starts to introduce audible Sonic
  artifacts on Piper-medium voices. Cap should not extend past 0.6.

#### 2. Sonic quality — flip to 1, ship it

- **Engine field:** `Sonic.setQuality(int)` — `0` (default) or `1`.
- **Type:** Hidden — not user-facing. One-shot constant change.
- **Default + range:** Engine default `0`. Sonic.java:204 comment:
  *"Default 0 is virtually as good as 1, but very much faster."*
- **What it does:** Selects the higher-quality interpolation kernel
  for pitch shifting. Marginal audible improvement, more CPU.
- **Why expose:** Don't expose. Just pick. Storyvox already pre-renders
  PCM (post-#97 cache work) so the CPU hit lands once per chapter, not
  per playback. Quality=1 might be worth the trade. Bench first.
- **Priority:** P0 (as a one-line flip, not a setting)
- **Cost to implement:** S — change `Sonic` constructor call site, A/B
  test on Tab A7 Lite.
- **Plumbing path:** (A) — but we'd need to thread a Sonic instance
  through `VoiceEngine.generateAudioPCM` rather than the inline
  ctor on line 210. Low touch.

#### 3. Punctuation-pause character: bring `()`/`—`/`;` into the table

- **Engine field:** N/A — this is storyvox's
  `EngineStreamingSource.trailingPauseMs()`, NOT VoxSherpa.
- **Type:** Internal (data table change), no new UI.
- **Default + range:** Today: `.` `?` `!` → 350 ms; `;` `:` → 200 ms;
  `,` and dashes → 120 ms; fallback 60 ms. Each multiplied by the
  user's Off/Normal/Long selection.
- **What it does:** Storyvox splices fixed silence after each
  sentence's PCM. Currently terminal punctuation only.
- **Why expose:** VoxSherpa's `AudioEmotionHelper` (which we don't
  use) has a richer table including `...` (380 ms — slow ellipsis)
  and `।` (Devanagari full stop, 280 ms). Worth porting the ellipsis
  case at minimum — narrators audibly pause longer on `...` than `.`
  and listeners notice.
- **Priority:** P0
- **Cost to implement:** S — extend the regex + base-ms table in
  `EngineStreamingSource.trailingPauseMs()`.
- **Plumbing path:** (A) — storyvox-internal.
- **Risk:** none.

#### 4. Voice-determinism toggle (noise_scale & noise_scale_w)

- **Engine field:** `OfflineTtsVitsModelConfig.setNoiseScale()` +
  `setNoiseScaleW()` (Piper); same for Matcha. Kokoro has no
  noise_scale.
- **Type:** Boolean toggle ("Steady voice"). Internally toggles
  between **VoxSherpa-default** (`0.35` / `0.667` — already calmed
  vs upstream) and **Vanilla** (`0.667` / `0.8` — sherpa-onnx
  defaults, more expressive but more take-to-take variation).
- **Default + range:** Default = Steady (current behavior).
- **What it does:** Lower noise_scale = drier, more deterministic
  voice; identical text re-renders sound nearly identical.
  Higher = the engine samples more from its prior, giving slightly
  different prosody on each generation. For an audiobook listener
  who replays a chapter, deterministic is preferable.
- **Why expose:** A nontrivial number of users dislike VoxSherpa's
  "calm" Piper output and want closer-to-vanilla Piper. Two presets
  (Steady/Expressive) covers that without pushing four-decimal
  sliders at users.
- **Priority:** P0 — but also the **first knob that requires the
  VoxSherpa fork** (B-path).
- **Cost to implement:** M — needs `VoiceEngine.setNoiseScale()` and
  `setNoiseScaleW()` setters that destroy + reload model. Upstream
  PR + tag.
- **Plumbing path:** (B) (or (C) if we decide to wrap sherpa-onnx
  directly).
- **Risk:** Settings change forces model reload (~1-3s on Piper, ~30s
  on Kokoro). UX needs a "applying voice settings…" spinner.

### P1 — Nice-to-have, do after P0

#### 5. Speed range — widen past 3.0×

- **Engine field:** `OfflineTts.generate(text, sid, speed)` (engine
  applies as `length_scale = 1/speed`).
- **Type:** Slider (already exists, raise cap).
- **Default + range:** Current UI 0.5..3.0; engine accepts up to ~5×
  before audible degradation; floor below 0.5 starts dragging.
- **What it does:** Faster/slower delivery. Above 3× the model
  predicts shorter durations per phoneme; shorter PCM → playback
  is just faster, no Sonic interpolation involved.
- **Why expose:** Power users (commute listeners) want 3.5× / 4×.
  Current cap is conservative. Cap of 4.0× is reasonable; intelligibility
  still survives most voices.
- **Priority:** P1
- **Cost to implement:** S — slider range constant.
- **Plumbing path:** (A)

#### 6. Duration variability (length_scale separate from speed)

- **Engine field:** `OfflineTtsVitsModelConfig.setLengthScale()` and
  `OfflineTtsKokoroModelConfig.setLengthScale()`.
- **Type:** Float slider (Pro).
- **Default + range:** Default 1.0; sane 0.7..1.3.
- **What it does:** Static multiplier baked into the model build. Used
  *additionally* to per-call `speed`. Useful if the user wants a
  "naturally slower" voice without it sounding like 0.8× speed
  (because length_scale also tweaks duration variance, not just
  duration).
- **Why expose:** Maybe — it's redundant with `speed` for most users.
  The genuine win is when the per-call speed feels "robotic" past 1.4×
  but bumping length_scale 0.85 + speed 1.0 sounds like a faster
  natural voice.
- **Priority:** P1, debatable. Could equivalently be folded into
  speed.
- **Cost to implement:** M — VoxSherpa fork required (path B).
- **Plumbing path:** (B)

#### 7. Per-voice pitch/speed defaults

- **Engine field:** N/A — storyvox-side memory.
- **Type:** Implicit (when user sets pitch on voice X, remember it
  per-voice).
- **Default + range:** Current default applies to all voices.
- **What it does:** Different voices have different "natural pitch
  centers" — `am_michael` (American Male) sits 5% lower than
  `af_bella`. If the user dials `bella -10%` to taste, switching to
  `michael` shouldn't apply that same -10% (now too low).
- **Why expose:** Quality-of-life for listeners who curate multiple
  voices.
- **Priority:** P1
- **Cost to implement:** M — schema change in voice settings, migration
  for existing single global pitch.
- **Plumbing path:** (A) — storyvox-internal.

#### 8. Silence_scale (top-level OfflineTtsConfig)

- **Engine field:** `OfflineTtsConfig.setSilenceScale()` — default 0.2,
  VoxSherpa sets 0.2 on Kokoro and leaves default on VITS.
- **Type:** Float slider OR fold into the existing punctuation-pause
  selector.
- **Default + range:** Default 0.2; range 0.0..2.0.
- **What it does:** Scales internal pause-token durations the model
  emits between phrases (NOT the same as storyvox's per-sentence
  splice). Some Kokoro speakers have noticeable mid-sentence pauses
  before commas; bumping silence_scale extends them, dropping
  shrinks them.
- **Why expose:** The current "Pause after punctuation" UI controls
  *between-sentence* pause. silence_scale controls *within-sentence*
  pause. If users complain that "Long" pause makes commas feel awkward,
  silence_scale is the actual lever to pull. Probably fold into the
  existing selector as a bonus modifier (Off → silence_scale=0.0,
  Normal → 0.2, Long → 0.4).
- **Priority:** P1
- **Cost to implement:** M — VoxSherpa setter required (B). Or
  one-shot pick a value via the existing selector and bake it.
- **Plumbing path:** (B)

#### 9. Lexicon override (per-voice pronunciation dictionary)

- **Engine field:** `OfflineTtsVitsModelConfig.setLexicon()` and
  `OfflineTtsKokoroModelConfig.setLexicon()` — comma-separated paths.
- **Type:** Picker — "import a pronunciation file" + per-voice
  attached lexicon.
- **Default + range:** Empty (no override).
- **What it does:** Each line is a token + IPA/X-SAMPA phoneme
  sequence. Overrides the espeak-ng/jieba phonemization for those
  exact tokens.
- **Why expose:** **Royal Road / fanfic users have a real pain.**
  Made-up names ("Wei Wuxian", "Lianhua", "Aelindra") get
  mispronounced — sometimes consistently bad enough to make a
  chapter unlistenable. A user-editable per-voice lexicon is the
  industry-standard fix (every commercial TTS has one).
- **Priority:** P1
- **Cost to implement:** L — needs file UI, per-voice lexicon storage,
  model reload on change. VoxSherpa fork (B). Probably 2-3 PRs.
- **Plumbing path:** (B)
- **Open question:** UX shape — do we let users edit lexicon entries
  in-app (long-tail of users not knowing IPA) or only import
  prebuilt files? Probably "import + a known-good starter file
  shipped per-language."

#### 10. Kokoro language override

- **Engine field:** `OfflineTtsKokoroModelConfig.setLang()` — already
  set by VoxSherpa from `KokoroVoiceHelper.languageCode`.
- **Type:** Picker (Pro).
- **Default + range:** Auto from voice metadata. Override values:
  `en`, `es`, `fr`, `pt`, `it`, `de`, `hi`, `zh`, `ja`, etc. — see
  hexgrad/Kokoro-82M VOICES.md.
- **What it does:** Forces espeak phonemization to use a different
  language's rule set. E.g. English voice reading
  Spanish-dialogue-in-an-English-novel.
- **Why expose:** Niche but real. Royal Road has English novels with
  embedded foreign-language phrases that the Kokoro phonemizer
  butchers when it tries to use the voice's home language.
- **Priority:** P1
- **Cost to implement:** M — VoxSherpa setter (B). UX is straightforward
  picker.
- **Plumbing path:** (B)

### P2 — Hidden / Pro / unlikely-to-ship

#### 11. ONNX provider override

- **Engine field:** `OfflineTtsModelConfig.setProvider()` — `"cpu"`,
  `"xnnpack"`, `"nnapi"`, `"cuda"`, `"coreml"`.
- **Type:** Pro picker.
- **Default + range:** VoxSherpa tries `xnnpack` then falls back to
  `cpu` automatically.
- **What it does:** Switches the ONNX runtime backend. NNAPI in
  theory routes to a device's NPU; in practice on the Tab A7 Lite
  (Helio P22T) there's no NPU and NNAPI degrades to CPU.
- **Why expose:** Borderline useless to ship — auto-fallback already
  picks the right one. Could add as a `Pro` debugging knob with a
  warning that "wrong provider may crash."
- **Priority:** P2
- **Cost to implement:** M — VoxSherpa setter (B), and we'd want to
  preserve the fallback semantics.
- **Plumbing path:** (B)

#### 12. num_threads override

- **Engine field:** `OfflineTtsModelConfig.setNumThreads()` —
  VoxSherpa picks 1..4 from core count.
- **Type:** Pro slider.
- **Default + range:** Auto (1..4).
- **What it does:** ONNX runtime intra-op thread count. Higher =
  faster synthesis, more battery, more thermal throttling.
- **Why expose:** Battery-savers might want to cap at 2 threads on
  long-running listening sessions even on 8-core devices to reduce
  hand-warmth + battery drain. Speed-runners on flagships might
  want 6+ threads (engine doesn't enforce a cap, but >cores is
  pointless).
- **Priority:** P2
- **Cost to implement:** M — VoxSherpa setter (B).
- **Plumbing path:** (B)

#### 13. Sonic.setRate / setVolume / setChordPitch

- **Engine field:** Sonic public methods, all unused by VoxSherpa.
- **Type:** Pro toggles + sliders.
- **Default + range:**
  - rate: 1.0; 0.5..2.0 — combined pitch+speed. Redundant with engine speed.
  - volume: 1.0; 0..2.0 — global gain.
  - chordPitch: false — alternate pitch algorithm tuned for vocal-source audio.
- **What it does:**
  - `rate`: scales pitch and speed together. Speed-only is what users
    actually want; rate is mostly redundant.
  - `volume`: pre-AudioTrack PCM gain. Risk of clipping above 1.0.
  - `chordPitch`: alternate pitch math. Sonic.java says it's tuned for
    vocal sources. Unclear if it sounds better than the default for
    Piper/Kokoro output.
- **Why expose:** Volume might be worth it if the user finds the
  device max volume + voice naturally too quiet. ChordPitch is an A/B
  question we could resolve internally.
- **Priority:** P2
- **Cost to implement:** S each — Sonic API is already linked.
- **Plumbing path:** (A)

#### 14. max_num_sentences

- **Engine field:** `OfflineTtsConfig.setMaxNumSentences()` — VoxSherpa
  picks 5 (Piper) and 3 (Kokoro).
- **Type:** Pro hidden int.
- **Default + range:** 1 upstream / 5 Piper / 3 Kokoro.
- **What it does:** Internal batch size. Larger batches = throughput
  win at cost of first-byte latency. Storyvox already calls
  `generateAudioPCM` per-sentence, so this only fires when one
  storyvox-sentence happens to internally split into multiple
  engine-sentences.
- **Why expose:** Don't. Bench-tune internally if anything.
- **Priority:** P2
- **Cost to implement:** M (B-path).
- **Plumbing path:** (B)

#### 15. rule_fsts / rule_fars (text normalization rules)

- **Engine field:** `OfflineTtsConfig.setRuleFsts()`,
  `setRuleFars()` — comma-separated paths to FST files.
- **Type:** Pro file picker.
- **Default + range:** Empty.
- **What it does:** Pre-tokenization text rewrites. Examples upstream:
  number expansion (`3.14` → `three point one four`), abbreviation
  expansion (`Dr.` → `Doctor`), date format (`12/25` → `December
  twenty-fifth`). FSTs are precompiled by k2-fsa tooling.
- **Why expose:** Solves a different audiobook pain than lexicon —
  rule_fsts handles "the engine read '12/25' as 'twelve slash
  twenty-five' instead of 'December twenty-fifth.'" Lexicon handles
  per-word phoneme overrides.
- **Priority:** P2 — opaque to non-technical users.
- **Cost to implement:** L — VoxSherpa setter (B), file UI, plus we
  probably need to *ship* a default-rules FST per language because
  building one from scratch needs k2-fsa tooling.
- **Plumbing path:** (B)
- **Open question:** Is anyone besides JP himself going to use this?

#### 16. Engine debug logging

- **Engine field:** `OfflineTtsModelConfig.setDebug(true)`.
- **Type:** Internal dev-only.
- **Default + range:** false.
- **What it does:** Verbose ONNX runtime logging.
- **Why expose:** Don't. Keep for storyvox debug builds only.
- **Priority:** P2
- **Plumbing path:** N/A — internal

### P2 — Emotion / advanced expression (separate spec recommended)

#### 17. Emotion-tag rendering — `[whisper]`, `[angry]`, `[sad]`, `[sarcastic]`, `[giggles]`

- **Engine field:** `AudioEmotionHelper.processAndGenerate(text,
  isPunctOn, isEmotionOn, baseSpeed, basePitch, baseVolume)`.
- **Type:** Boolean toggle in Settings → Reading.
- **Default + range:** Off.
- **What it does:** Each tag swaps a (volume, speed, pitch, attack-time-ms)
  profile for the next chunk. Examples from VoxSherpa:
  - `[whisper]` — vol 0.65, speed×0.95, pitch×1.05, 2500ms attack
  - `[angry]` — vol 1.15, speed×1.05, pitch×0.95, 1500ms attack
  - `[sad]` — vol 0.80, speed×0.92, pitch×0.98, 2500ms attack
  - `[sarcastic]` — vol 1.0, speed×1.02, pitch×0.95, 1500ms attack
  - `[giggles]` — vol 1.10, speed×1.05, pitch×1.10, 1000ms attack
  - `[normal]` / `[]` — reset to user defaults
- **Why expose:** Fanfic-flagship feature. Royal Road authors use these
  exact bracket conventions. Storyvox could parse them inline and
  render emotion-aware audio without LLM-grade prosody control.
- **Priority:** P2 — **flagship-feature class, deserves its own spec.**
- **Cost to implement:** L — `AudioEmotionHelper` is batch-only
  (returns full PCM after processing whole text). To wire into
  storyvox's streaming pipeline we'd either:
  - (a) **AudioEmotionHelper-route** — call AEH per-sentence, accept
    that emotion changes don't ramp across sentence boundaries.
    Lose the attack-envelope feature.
  - (b) **Reimplement in Kotlin** — port AEH's profile-switching +
    attack-envelope logic into `EngineStreamingSource`. Full streaming.
    Larger lift, ~1 spec + 2-3 PRs.
- **Plumbing path:** (a) is (A), (b) is full re-implementation.
- **Open question:** does JP want this at all? It's a "voice acting"
  feature, distinct from "audiobook narration." Many listeners want
  flat narration and would find emotion tags actively annoying. Opt-in
  default-off setting is mandatory.

#### 18. Custom emotion-tag profile editor

- **Engine field:** `AudioEmotionHelper` profile dictionary, currently
  hardcoded.
- **Type:** Pro — JSON-editable emotion library.
- **Default + range:** Default 6 profiles ship; users can add tags
  like `[booming]`, `[creepy]`.
- **What it does:** Lets power users define their own bracket-tag
  vocabulary.
- **Why expose:** Flagship-of-flagship. Probably overshoots audience.
- **Priority:** P2
- **Cost to implement:** L+
- **Plumbing path:** (b) only

## Path-(C) sherpa-onnx direct wrapper

A meta-recommendation: if JP green-lights more than one (B)-path knob
above (especially #4 Voice-determinism, #6 length_scale, #8
silence_scale, #9 lexicon, #10 lang, #11 provider, #12 num_threads,
#14 max_num_sentences, #15 rule_fsts), it becomes cheaper to wrap
sherpa-onnx directly than to upstream that many setters.

The work is roughly:

1. New `core-playback/.../tts/sherpa/SherpaTtsEngine.kt` — Kotlin
   class holding `OfflineTts`, exposing every config field as a Kotlin
   property with a setter that triggers `model.release()` + rebuild.
2. Reuse VoxSherpa's espeak-ng-data extraction logic (it's the only
   non-trivial piece — verbatim port).
3. Reuse Sonic for pitch (drop the singleton-VoiceEngine path; pitch
   becomes a Sonic instance threaded through generate calls).
4. Drop `KokoroVoiceHelper` reliance — bake the speaker list into our
   own `KokoroVoiceCatalog` (already partially done in
   `core-playback/.../voice/VoiceCatalog.kt`).

Effort: ~1 spec + 2-3 PRs (M-L). Payoff: every future engine knob is
a one-line change. Cost: divergence from jphein's upstream means we
don't get free upgrades when sherpa-onnx adds a new model type
(Zipvoice / Supertonic are already present in trunk but absent from
1.12.26).

## Recommended P0 bundle (one PR)

If JP says "ship the obvious wins" and stops there, the P0 bundle is:

1. **#1 widen pitch range** to 0.7..1.3 (slider constant).
2. **#3 add `...` to punctuation-pause table** (regex + 380ms case).
3. **#2 set Sonic.quality = 1** (one constructor arg flip), iff a
   Tab A7 Lite bench shows <10% CPU regression.

Three changes, one PR, all path-(A), no fork required.

## Recommended P0+ bundle (two PRs)

Add **#4 Steady/Expressive toggle** (the noise_scale presets) on a
follow-up. This one needs the VoxSherpa fork — first taste of
path-(B) — but it's the highest-impact engine-side knob and a good
forcing function for the upstream-PR muscle.

After that, evaluate path-(C) before adding more (B)-path knobs.

## Open questions for JP

1. **Path-(C) timing.** Do we want to wrap sherpa-onnx directly now
   (one-time spec + work, then everything is cheap) or stick with
   VoxSherpa upstreaming until the cost of N forks outweighs the
   wrapper? Recommend: stay on VoxSherpa for #4, decide after.

2. **#9 lexicon override** — high listener value, but the UX is
   nontrivial. JSON file picker only? Inline editor? Per-voice
   lexicon vs global lexicon? Could be its own spec.

3. **#17 emotion tags** — opt-in flagship feature or distraction?
   Royal Road audience would react warmly; "I just want my book read"
   audience would react with WTF. Default-off solves UX but doesn't
   answer "is this our flagship differentiator vs Azure HD?" question.

4. **`noise_scale` exposure level** — Boolean toggle (Steady/Expressive
   preset) is JP-friendly. Float slider is "you broke your voice and
   it's your fault." Recommend toggle, not slider — but it's a bias
   call.

5. **`length_scale` vs `speed`** — fold together or keep separate?
   They overlap functionally for users; the only real difference is
   length_scale changes prosody slightly, speed is a pure time-stretch.
   Recommend: fold into `speed` slider, don't expose length_scale
   separately. (Removes #6 from the catalogue.)

6. **`rule_fsts` (#15)** — does anyone besides JP himself want this?
   Probably skip until a user asks.

7. **VoxSherpa upstream relationship** — jphein's fork is one
   developer. If they go quiet, do we have the code-archaeology
   confidence to fork-the-fork or to skip to path-(C)? This is a
   risk-distribution question more than a technical one.

## Summary table

| # | Knob | Priority | Path | Cost |
|---|---|---|---|---|
| 1 | Pitch range widen | P0 | A | S |
| 2 | Sonic quality flip | P0 | A | S |
| 3 | Add `...` pause | P0 | A | S |
| 4 | Steady/Expressive toggle (noise_scale) | P0 | B | M |
| 5 | Speed range widen past 3× | P1 | A | S |
| 6 | length_scale slider | P1 | B | M |
| 7 | Per-voice pitch defaults | P1 | A | M |
| 8 | silence_scale | P1 | B | M |
| 9 | Lexicon override | P1 | B | L |
| 10 | Kokoro lang override | P1 | B | M |
| 11 | Provider picker | P2 | B | M |
| 12 | num_threads override | P2 | B | M |
| 13 | Sonic rate/volume/chordPitch | P2 | A | S |
| 14 | max_num_sentences | P2 | B | M |
| 15 | rule_fsts file picker | P2 | B | L |
| 16 | Engine debug logging | P2 | (internal) | - |
| 17 | Emotion tags toggle | P2 | A or (b) | L |
| 18 | Custom emotion profiles | P2 | (b) | L+ |

S = ~1 PR; M = 2-3 PRs; L = needs spec + several PRs.

---

*Research output for future implementation. Implementation begins
when JP picks a P0 bundle to commission.*
