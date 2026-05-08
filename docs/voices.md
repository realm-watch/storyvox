---
layout: default
title: Voice catalog
description: Piper and Kokoro voices shipped via VoxSherpa-TTS. Sizes, quality tiers, refresh workflow, and why we don't quantize.
---

# Voice catalog

storyvox ships **two voice families**:

- **[Piper](https://github.com/rhasspy/piper)** — single-speaker, compact, fast. Each voice is ~14–30 MB. Best for first-time installs and for fast turnaround on slow hardware.
- **[Kokoro](https://github.com/hexgrad/kokoro)** — multi-speaker, ~330 MB single download, ships dozens of voice profiles in one model. Higher quality, slower to synthesize.

Both run **in-process** in storyvox via the [VoxSherpa-TTS](https://github.com/jphein/VoxSherpa-TTS) `:engine-lib` AAR, which wraps [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) for inference. No second APK, no system-TTS handoff, no per-character billing.

## Quality tiers (Piper)

Piper voices ship in three quality tiers from the upstream `vits-piper-*` models:

| Tier | Approx. download | Synthesis speed | Notes |
|------|------------------|-----------------|-------|
| **low** | ~14 MB | Fastest | Best for very slow devices; some artifacting on long sentences. |
| **medium** | ~22 MB | Mid | Default tier. Good balance. |
| **high** | ~28 MB | Slowest | Most natural prosody; can run below realtime on Helio P22T-class chips (see [Performance modes](Performance-modes/) on the wiki). |

Voice tiers are independent of speaker — pick the speaker first, then the tier you can afford.

## Where voices live

Model weights are **not bundled in the APK** (they'd add 1+ GB). They download on demand from the
[`voices-v2`](https://github.com/jphein/VoxSherpa-TTS/releases/tag/voices-v2) GitHub release on
`jphein/VoxSherpa-TTS`. Each Piper voice ships as `{lang}-{voice}-{quality}.onnx` plus a
`.tokens.txt` sidecar. Kokoro ships as `kokoro-model.onnx` + `kokoro-voices.bin` + `kokoro-tokens.txt`.

`voices-v2` is a re-hosting of the upstream k2-fsa tarballs as flat single-file downloads — extracting `.tar.bz2` archives on Tab A7 Lite-class hardware is slow enough to delay the first chapter for tens of seconds. Doing it once server-side moves that cost off the device.

## Why fp32, not int8

The previous `voices-v1` release used INT8-quantized models — ~3× smaller download, but a vocoder
run through INT8 dynamic quantization adds audible noise to spectral coefficients. That's the
"fuzz" symptom users heard on Samsung tablets in v0.4.x.

`voices-v2` uses **fp32** weights — exactly what's inside the upstream
`vits-piper-*.tar.bz2` and `kokoro-multi-lang-v1_1.tar.bz2` archives. Larger downloads, clean speech.

The `_int8` suffix in some `VoiceCatalog.kt` voice IDs (e.g. `piper_lessac_en_US_high_int8`)
is **historical** — kept stable so already-installed users don't have to re-pick a voice when they
update. The on-disk files and URLs no longer involve quantization.

## Picking a voice

In the app: **Settings → Voice library**, or tap the voice name in **Settings → Voice & Playback** to jump there.

The library groups voices by engine (Piper / Kokoro), language, and speaker. Each row shows:

- **Star** — toggle to favorite a voice. Stars persist across reinstalls and sit at the top of the list.
- **Status** — installed, downloading, available, or starred.
- **Size** — disk footprint after install.
- **Quality tier** — for Piper voices.

The current selection is highlighted in brass. Tapping a voice that isn't installed kicks off the download with a progress bar and notification; the voice becomes selectable when it lands.

### Disk hygiene

- **Long-press a voice → Delete** to free disk. The currently-selected voice can't be deleted; switch first.
- The starred surface in the library shows your favorite voices at the top regardless of install status. Use it as a shortlist when you reinstall.

## Catalog refresh workflow (maintainers)

```bash
# Compare upstream to voices-v2 (no changes, no auth needed)
./scripts/voices/refresh-voices-v2.sh --check-only

# Pull new tarballs, extract, upload (needs gh CLI auth with write
# access to jphein/VoxSherpa-TTS; uses ~5 GB temp space)
./scripts/voices/refresh-voices-v2.sh
```

The script:

1. Lists `tts-models` assets on `k2-fsa/sherpa-onnx` matching `vits-piper-(en_US|en_GB)-*.tar.bz2` (no `-int8`, no `-fp16` suffix) and `kokoro-multi-lang-v1_1.tar.bz2`.
2. Diffs against assets currently on `voices-v2`.
3. Downloads any missing tarballs (parallel x8).
4. Extracts and flattens — drops `MODEL_CARD`, `espeak-ng-data/`, and various Kokoro lexicon/dict files (storyvox bundles its own espeak data).
5. Uploads everything to `voices-v2` with `--clobber` so reruns are idempotent.

When you add a new upstream voice, also add a `CatalogEntry` to
`core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/VoiceCatalog.kt`. The script
**does not** edit the catalog automatically — that part is a deliberate human review.

## Automated drift detection

`.github/workflows/voice-catalog-check.yml` runs on the 1st of every month (and on manual
dispatch). It performs the same diff as `--check-only` and, if there's drift, files a GitHub
issue listing new upstream voices. It does **not** auto-publish — refreshing `voices-v2` requires
write access to `jphein/VoxSherpa-TTS`, which the default `GITHUB_TOKEN` doesn't have.

## Why we don't auto-update the in-app catalog

`VoiceCatalog.kt` is compiled into the APK. To pick up new voices the user has to install a new
app version. That's intentional:

- The catalog includes language tags, quality tier hints, and recommended voices that need human curation.
- Adding voices is rare enough (months between k2-fsa releases) that a per-release cadence doesn't hurt.
- A network-fetched dynamic catalog would add a runtime failure mode and an "is the index loading?" UX state. Not worth it for the cadence.

If the cadence ever picks up, the plan is to switch to a JSON file in the same `voices-v2`
release that the app fetches and caches; `VoiceCatalog.kt` becomes a fallback. That's a v1.x
feature, not v0.4.x.

## Coming soon

- **Azure HD voices** ([#85](https://github.com/jphein/storyvox/issues/85)) — bring-your-own-key Azure Cognitive Services voices for users who want studio-grade narration. Optional, not required, never billed by storyvox. Design draft is in `docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md`.
- **VoxSherpa-TTS knob exposure** ([research draft](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md)) — loudness normalization, breath pause, pitch envelope as user-tunable settings.
