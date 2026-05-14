---
layout: default
title: Architecture
description: storyvox's thirteen Gradle modules, fiction-source plug-in pattern, in-process TTS engine, and optional cloud TTS backend.
---

# Architecture

storyvox is **thirteen Gradle modules**. The split keeps fiction sources, playback, UI, and theming independently testable, and makes adding a new fiction source (or swapping the TTS backend) a localized change. Each new source we've added since the original architecture — RSS, EPUB, Outline, Azure HD voices, the AI-chat provider matrix — landed as its own module without touching the others.

```
┌─────────────────────────────────────────────┐
│  :app                                       │
│  Hilt root · NavHost · Settings adapters    │
└──────┬──────────────┬───────────────────────┘
       │              │
       │              ▼
       │       ┌──────────────────────┐
       │       │  :feature            │
       │       │  Library / Follows / │
       │       │  Browse / Reader /   │
       │       │  Detail / Settings / │
       │       │  AI Chat             │
       │       └──┬──────┬─────┬──────┘
       │          │      │     │
       ▼          ▼      ▼     ▼
┌────────────┐ ┌─────────────┐ ┌───────────────┐ ┌───────────────┐
│ :core-data │ │ :core-      │ │  :core-llm    │ │  :core-ui     │
│  Room +    │ │  playback   │ │  Provider     │ │  Library      │
│  repos +   │ │  EnginePlyr │ │  matrix       │ │  Nocturne     │
│  Fiction   │ │  + PcmCache │ │  (Claude /    │ │  theme +      │
│  Source    │ │  + Voice    │ │   Teams /     │ │  components   │
│  Map       │ │  Manager +  │ │   OpenAI /    │ │               │
│            │ │  Sentence   │ │   Vertex /    │ │               │
│            │ │  Tracker    │ │   Bedrock /   │ │               │
│            │ │  (in-proc + │ │   Foundry /   │ │               │
│            │ │   Azure)    │ │   Ollama)     │ │               │
└─────┬──────┘ └──────┬──────┘ └───────────────┘ └───────────────┘
      │               │
      ▼               │ JitPack: VoxSherpa-TTS :engine-lib
┌──────────────────────┐  (Piper + Kokoro + sherpa-onnx, in-process)
│ Fiction sources      │
│ ──────────────────── │
│ :source-royalroad    │  Cloudflare-aware fetch, login WebView,
│                      │  honeypot filter, full filter sheet
│ :source-github       │  GitHub API + book.toml + storyvox.json +
│                      │  commonmark renderer + OAuth Device Flow
│ :source-rss          │  RSS / Atom parser + storyvox-feeds registry
│ :source-epub         │  SAF folder picker + OPF parser
│ :source-outline      │  Outline wiki API client
│ :source-mempalace    │  LAN-only MemPalace daemon client
│                      │
│ TTS backends         │
│ ──────────────────── │
│ :source-azure        │  Azure Cognitive Services HD voices
│                      │  (BYOK, remote, with offline fallback)
└──────────────────────┘
                         ▼
                   (audio out)
```

## Module map

| Module | Role | Key types |
|---|---|---|
| `:app` | Hilt root, NavHost, top-level wiring. | `StoryvoxApp`, `MainActivity`, `AppBindings` |
| `:feature` | Compose UI for every screen. | `LibraryScreen`, `BrowseScreen`, `ReaderScreen`, `SettingsScreen`, `VoiceLibraryScreen`, `ChatScreen` |
| `:core-data` | Room schema, repositories, fiction-source contract. | `FictionSource`, `ChapterRepository`, `FictionRepository`, `SettingsRepositoryUi` |
| `:core-playback` | Audio engine, voice management, sentence tracking, multi-engine producer. | `EnginePlayer`, `EngineStreamingSource`, `PcmCache`, `VoiceManager`, `VoiceCatalog`, `SentenceTracker` |
| `:core-llm` | Provider matrix for AI chat — Claude direct, Anthropic Teams (OAuth), OpenAI, Vertex, Bedrock, Foundry, Ollama. Isolates HTTP clients per provider. | `LlmProvider`, `ChatRepository`, `GroundingContext`, `RecapEngine` |
| `:core-ui` | Library Nocturne theme, shared components. | `LibraryNocturneTheme`, `BrassButton`, `BrassProgressTrack`, spacing/color tokens |
| `:source-royalroad` | Royal Road implementation of `FictionSource`. | `RoyalRoadSource`, `RoyalRoadFetcher`, `RoyalRoadParsers`, `LoginWebView` |
| `:source-github` | GitHub-repo implementation of `FictionSource`. | `GithubSource`, `GithubFetcher`, `BookTomlParser`, `CommonmarkRenderer`, `DeviceFlowAuth` |
| `:source-rss` | RSS / Atom-feed implementation. Pulls suggested-feeds list from [storyvox-feeds](https://github.com/techempower-org/storyvox-feeds). | `RssSource`, `RssFetcher`, `RssParser`, `RssFeed` |
| `:source-epub` | Local-EPUB-folder implementation via the Storage Access Framework + OPF parser. | `EpubSource`, `EpubParser`, `EpubModels` |
| `:source-outline` | Outline (self-hosted wiki) implementation — collections as fictions, articles as chapters. | `OutlineSource`, `OutlineApi`, `OutlineConfig` |
| `:source-mempalace` | Read-only LAN-only [MemPalace](https://github.com/techempower-org/mempalace) source — wings as collections, rooms as fictions, drawers as chapters. | `MempalaceSource`, `MempalaceFetcher`, `PalaceDaemonClient` |
| `:source-azure` | Optional remote TTS backend — Azure Cognitive Services HD voices via SSML. BYOK; falls back to a local voice if the network drops or your key fails. | `AzureVoiceEngine`, `AzureSpeechClient`, `AzureSsmlBuilder`, `AzureCredentials` |
| `:wear` | Wear OS companion (experimental). | `WearMainActivity`, `WearTransport` |

## Fiction-source plug-in pattern

Fiction sources are bound into a `Map<String, FictionSource>` via Hilt `@IntoMap @StringKey`. Adding a new source is roughly:

1. Implement `FictionSource` in a new `:source-foo` module — `browse()`, `detail(id)`, `chapter(id)`, `latestSha(id)` etc.
2. Add a Hilt module that binds the implementation `@IntoMap @StringKey("foo")`.
3. Register the source key in the Browse tab's source picker.

`:core-data`'s `FictionSourceMap` injects the map and routes calls by the `sourceKey` stored on each fiction. UI never imports a source module directly. RSS, EPUB, and Outline all landed against this contract without changes elsewhere — the abstraction is paying for itself.

## TTS-backend plug-in pattern

Same shape, applied to voice engines. The local Piper/Kokoro engine and the optional Azure HD backend both implement a `VoiceEngine` interface in `:core-playback` and bind into a `Map<EngineKind, VoiceEngine>`. `EnginePlayer` routes synthesis to whichever engine the currently-selected voice belongs to, with offline fallback to the local engine if Azure errors or the network drops mid-chapter.

## Playback pipeline

Playback is **independent of UI**. `EnginePlayer` is a Media3 `SimpleBasePlayer` subclass that exposes a standard player surface to MediaSession (lock-screen art, BT transport, headphone media buttons) while internally pipelining sentence synthesis against AudioTrack writes.

```
sentences ──► VoiceEngine (Piper or Kokoro inference)
              │
              ▼
       ┌──────────────────┐
       │  Producer        │  (tts.synthesize)
       │  prefetch queue  │
       │  8 PCM chunks    │
       └────┬─────────────┘
            │
            ▼
       ┌──────────────────┐
       │  PcmCache        │  ← optional disk cache (PCM bytes + sentence index)
       │  appender        │
       └────┬─────────────┘
            │
            ▼
       ┌──────────────────┐
       │  URGENT_AUDIO    │  (consumer thread)
       │  → AudioTrack    │
       │  → SentenceTrack │
       └──────────────────┘
```

The PCM cache (landed in v0.4.31) renders each chapter's audio to disk on first play, so replays are gapless on any device. See the [PCM cache design spec](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) for the full pipeline diagram and cache-key rules.

For tuning tradeoffs (warm-up wait, catch-up pause, buffer headroom, full pre-render, punctuation cadence, multi-engine sliders), see **Settings → Performance** in the app.

### Tier 3 multi-engine producer

In v0.4.78+ the producer can run **1–8 VoxSherpa engine instances side-by-side**, each with its own thread pool. The sentence chunker hands work to a round-robin engine queue so the next sentence's chunks are already rendering before the current one finishes streaming to AudioTrack. Twin sliders in **Settings → Performance** let you tune Engines × Threads/engine for your CPU. The producer thread itself is pinned to `URGENT_AUDIO` priority so it doesn't get descheduled by background work mid-chapter.

## In-process TTS

storyvox links the local TTS engine **in-process** via the [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack). That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper and Kokoro engine wrappers into a single dependency.

We bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. **No second APK, no install gate, no engine-binding handshake** — synthesis runs in storyvox's own process.

Voice model weights are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release. See [Voices](voices/) for catalog details.

## Optional cloud TTS — Azure HD

For users who want studio-grade narration on slow devices, `:source-azure` wires Azure Cognitive Services HD voices into the same `VoiceEngine` interface the local engine uses. Add a key + region in **Settings → Voice & Playback → Azure** (BYOK; never billed by storyvox). The Azure engine uses SSML to drive the same per-voice speed/pitch/punctuation knobs you set for local voices, with retries on transient HTTP errors. If the key fails or the network drops mid-chapter, playback falls back to your selected local voice for the remainder of the chapter — it never just stops on you.

## Storage

- **Room** — fictions, chapters, follows, library state, voice download status.
- **DataStore Preferences** — settings (speed, pitch, theme, buffer headroom, mode toggles, multi-engine sliders, etc.).
- **EncryptedSharedPreferences** — Royal Road session cookies, GitHub OAuth tokens, Azure key + region.
- **Filesystem** — PCM cache files (`{cacheDir}/pcm/{chapterId}.{voiceId}.{speed}x.{pitch}.pcm`) plus sentence-index sidecars.
- **OkHttp disk cache** — chapter HTML and image responses.

## Specs and design docs

The `docs/superpowers/specs/` directory in the repo holds the canonical design specs:

- [`2026-05-05-storyvox-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-05-storyvox-design.md) — original architecture
- [`2026-05-06-github-source-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-06-github-source-design.md) — GitHub fiction source
- [`2026-05-07-pcm-cache-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) — chapter PCM cache
- [`2026-05-08-azure-hd-voices-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md) — Azure HD voices (shipped v0.4.61–v0.4.66)
- [`2026-05-08-github-oauth-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-github-oauth-design.md) — GitHub OAuth Device Flow (shipped)
- [`2026-05-08-settings-redesign-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-settings-redesign-design.md) — Settings UI redesign
- [`2026-05-08-voxsherpa-knobs-research.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md) — VoxSherpa knobs research
- [`2026-05-08-mempalace-integration-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-mempalace-integration-design.md) — MemPalace as fiction source
