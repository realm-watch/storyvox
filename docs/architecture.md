---
layout: default
title: Architecture
description: storyvox's eight Gradle modules, fiction-source plug-in pattern, and in-process TTS engine.
---

# Architecture

storyvox is **eight Gradle modules**. The split keeps fiction sources, playback, UI, and theming independently testable, and makes adding a new fiction source (or swapping the TTS engine) a localized change.

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
       │       │  Detail / Settings   │
       │       └──────┬─────┬─────────┘
       │              │     │
       ▼              ▼     ▼
┌────────────┐  ┌─────────────────┐  ┌───────────────┐
│ :core-data │  │ :core-playback  │  │  :core-ui     │
│  Room +    │  │  EnginePlayer + │  │  Library      │
│  repos +   │  │  PcmSource +    │  │  Nocturne     │
│  Fiction   │  │  VoiceManager + │  │  theme +      │
│  Source    │  │  SentenceTracker│  │  components   │
│  Map       │  │  (in-proc TTS)  │  │               │
└─────┬──────┘  └────────┬────────┘  └───────────────┘
      │                  │
      ▼                  │ JitPack: VoxSherpa-TTS :engine-lib
┌──────────────────────┐ │ (Piper + Kokoro + sherpa-onnx)
│ :source-royalroad    │ │
│  Cloudflare-aware    │ │
│  fetch, parsers,     │ │
│  login WebView,      │ │
│  honeypot filter     │ │
└──────────────────────┘ │
┌──────────────────────┐ │
│ :source-github       │ │
│  GitHub API client,  │ │
│  book.toml +         │ │
│  storyvox.json,      │ │
│  commonmark renderer │ │
└──────────────────────┘ │
                         ▼
                   (audio out)
```

## Module map

| Module | Role | Key types |
|---|---|---|
| `:app` | Hilt root, NavHost, top-level wiring. | `StoryvoxApp`, `MainActivity`, `AppBindings` |
| `:feature` | Compose UI for every screen. | `LibraryScreen`, `BrowseScreen`, `ReaderScreen`, `SettingsScreen`, `VoiceLibraryScreen` |
| `:core-data` | Room schema, repositories, fiction-source contract. | `FictionSource`, `ChapterRepository`, `FictionRepository`, `SettingsRepositoryUi` |
| `:core-playback` | Audio engine, voice management, sentence tracking. | `EnginePlayer`, `EngineStreamingSource`, `PcmCache`, `VoiceManager`, `VoiceCatalog`, `SentenceTracker` |
| `:core-ui` | Library Nocturne theme, shared components. | `LibraryNocturneTheme`, `BrassButton`, `BrassProgressTrack`, spacing/color tokens |
| `:source-royalroad` | Royal Road implementation of `FictionSource`. | `RoyalRoadSource`, `RoyalRoadFetcher`, `RoyalRoadParsers`, `LoginWebView` |
| `:source-github` | GitHub-repo implementation of `FictionSource`. | `GithubSource`, `GithubFetcher`, `BookTomlParser`, `CommonmarkRenderer` |
| `:wear` | Wear OS companion (experimental). | `WearMainActivity`, `WearTransport` |

## Fiction-source plug-in pattern

Sources are bound into a `Map<String, FictionSource>` via Hilt `@IntoMap @StringKey`. Adding a new source is roughly:

1. Implement `FictionSource` in a new `:source-foo` module — `browse()`, `detail(id)`, `chapter(id)`, `latestSha(id)` etc.
2. Add a Hilt module that binds the implementation `@IntoMap @StringKey("foo")`.
3. Register the source key in the Browse tab's source picker.

`:core-data`'s `FictionSourceMap` injects the map and routes calls by the `sourceKey` stored on each fiction. UI never imports a source module directly.

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

The PCM cache (landed in v0.4.31) renders each chapter's audio to disk on first play, so replays are gapless on any device. See the [PCM cache design spec](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) for the full pipeline diagram and cache-key rules.

For tuning tradeoffs (warm-up wait, catch-up pause, buffer headroom, full pre-render, punctuation cadence), see the wiki's [Performance modes](https://github.com/jphein/storyvox/wiki/Performance-modes) page.

## In-process TTS

storyvox links the TTS engine **in-process** via the [VoxSherpa-TTS](https://github.com/jphein/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack). That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper and Kokoro engine wrappers into a single dependency.

We bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. **No second APK, no install gate, no engine-binding handshake** — synthesis runs in storyvox's own process.

Voice model weights are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release. See [Voices](voices/) for catalog details.

## Storage

- **Room** — fictions, chapters, follows, library state, voice download status.
- **DataStore Preferences** — settings (speed, pitch, theme, buffer headroom, mode toggles, etc.).
- **EncryptedSharedPreferences** — Royal Road session cookies.
- **Filesystem** — PCM cache files (`{cacheDir}/pcm/{chapterId}.{voiceId}.{speed}x.{pitch}.pcm`) plus sentence-index sidecars.
- **OkHttp disk cache** — chapter HTML and image responses.

## Specs and design docs

The `docs/superpowers/specs/` directory in the repo holds the canonical design specs:

- [`2026-05-05-storyvox-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-05-storyvox-design.md) — original architecture
- [`2026-05-06-github-source-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-06-github-source-design.md) — GitHub fiction source
- [`2026-05-07-pcm-cache-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) — chapter PCM cache
- [`2026-05-08-azure-hd-voices-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md) — Azure HD voices (future)
- [`2026-05-08-github-oauth-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-08-github-oauth-design.md) — GitHub OAuth (future)
- [`2026-05-08-settings-redesign-design.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-08-settings-redesign-design.md) — Settings UI redesign
- [`2026-05-08-voxsherpa-knobs-research.md`](https://github.com/jphein/storyvox/blob/main/docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md) — VoxSherpa knobs research
