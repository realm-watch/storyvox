# storyvox

[![Build](https://github.com/techempower-org/storyvox/actions/workflows/android.yml/badge.svg)](https://github.com/techempower-org/storyvox/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/techempower-org/storyvox?color=b88746&label=release)](https://github.com/techempower-org/storyvox/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-b88746.svg)](LICENSE)
[![Built by dream-team](https://img.shields.io/badge/built%20by-dream--team-7d5fff.svg)](#how-it-was-built)

**A neural-voice audiobook player for any text you have.**
Stream chapters from [Royal Road](https://royalroad.com), [GitHub](https://github.com/), an [Outline](https://www.getoutline.com) wiki, an RSS / Atom feed, a [Memory Palace](https://github.com/techempower-org/mempalace) you host yourself, or a folder of EPUB files on your device — read aloud by an offline neural TTS engine. A hybrid reader/audiobook view highlights the spoken sentence in brass as you listen. Built for Android phones, tablets, and Wear OS.

<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/03-reader.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/screenshots/03-reader-light.png">
    <img src="docs/screenshots/03-reader.png" width="320" alt="storyvox reader playing The Archmage Coefficient" />
  </picture>
</div>

> **v0.5.09** — six fiction sources (Royal Road, GitHub, RSS, EPUB, Outline, Memory Palace), Azure HD voices as an optional cloud TTS backend (BYOK), Tier 3 multi-engine parallel synthesis (1–8 engines × N threads each), smart-resume CTA, AI chat per fiction across seven LLM providers, GitHub OAuth, Settings redesign (8 sections), shake-to-extend sleep timer. GPL-3.0 (downstream of the engine, not a posture choice — see [License](#license)).

---

## What it does

- **Six fiction sources, side by side.** Browse [Royal Road](https://royalroad.com) with the full filter set (tags include/exclude, status, type, length, rating, content warnings, sort); browse fiction repos on GitHub via the curated [storyvox-registry](https://github.com/techempower-org/storyvox-registry) plus live `/search/repositories` results; subscribe to any **RSS / Atom feed** with a managed suggested-feeds list from [storyvox-feeds](https://github.com/techempower-org/storyvox-feeds); pull articles from a self-hosted **[Outline](https://www.getoutline.com)** wiki; mount a **[Memory Palace](https://github.com/techempower-org/mempalace)** you host yourself; or open **local EPUB files** from any folder via the system file picker. Each backend has its own on/off toggle in Settings.
- **Plays chapters as audiobooks** through an in-process neural TTS engine. Two voice families ship: [Piper](https://github.com/rhasspy/piper) (compact, ~14–30 MB) and [Kokoro](https://github.com/hexgrad/kokoro) (multi-speaker, ~330 MB). Voice models download on demand from `voices-v2`; nothing is bundled in the APK. No cloud, no API keys, no per-character billing.
- **Optional cloud voices** — bring-your-own-key [Azure Cognitive Services HD voices](https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech) for studio-grade narration on slow devices. Offline fallback to the local engine if your key fails or the network drops. Azure is opt-in, never required, never billed by storyvox.
- **Tier 3 multi-engine parallel synthesis.** Run 1–8 VoxSherpa engine instances side-by-side, each with its own thread pool, so a single sentence's chunks render in parallel and the next sentence is already queued before the current one finishes. Twin sliders in **Settings → Performance** (Engines, Threads/engine) let you tune for your CPU. The producer pins to a dedicated `URGENT_AUDIO` thread to keep audio scheduling honest under load.
- **Highlights the current sentence** in brass as the engine speaks. Swipe between audiobook view (cover, scrubber, transport) and reader view (chapter text). The highlight glides between sentences to match the read-aloud rhythm.
- **Auto-advances** between chapters. Eager-downloads ahead so the next chapter is ready when the current ends. PCM cache buffering keeps playback smooth when synthesis falls behind — the player pauses, refills, resumes without a glitch.
- **AI chat per fiction.** Per-book chat sessions across seven LLM providers (Claude direct, Anthropic Teams via OAuth, OpenAI, Vertex, Bedrock, Foundry, Ollama) with grounding controls — feed the AI the current sentence, the entire chapter, or the entire book so far. Long-press a word to ask "Who is X?". AI-generated chapter recaps you can read aloud through the same TTS pipeline.
- **GitHub sign-in via OAuth Device Flow** (no API key paste). Lifts the anon 60 req/hr cap to 5,000, unlocks "My Repos" / "Starred" / "Gists" tabs in Browse, and (opt-in) private-repo access for treating private repos as your personal book library.
- **Voice library with tiers and favorites.** Engine-grouped picker, star toggles for the voices you keep coming back to, and a Starred surface that floats them to the top.
- **Sleep timer** with 15/30/45/60-minute presets, an "end of chapter" mode, a countdown pulse as time runs out, and shake-to-extend during the fade-out tail (#150).
- **Smart-resume CTA** — the Library "Resume" button respects your last paused/playing intent so it never auto-plays at you when you opened the app to *read*.
- **Library + Follows tabs** with sign-in via WebView (your Royal Road follow list syncs into the app).
- **Infinite-scroll Browse** across every tab.
- **Cheap polling for new chapters.** GitHub-sourced fictions watch the repo's HEAD SHA; the manifest is only re-scanned when something changes — one HTTP request per fiction per check.
- **MediaSession-aware** — lock-screen art, transport from Bluetooth headsets, headphone media buttons, notification shade.
- **Settings redesign** — 8 sections in touch-frequency order (Voice & Playback, Reading, Performance, AI, Library & Sync, Account, Memory Palace, About) with brass section icons and a unified component vocabulary.
- **Library Nocturne theme** — brass on warm dark, EB Garamond chapter body, Inter UI. Light mode is parchment cream.
- **Adaptive layouts** — fills the screen on phones (2 columns), tablets (5), foldables (more).

## Screens

<table>
<tr>
<td align="center"><b>Browse</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/01-browse.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/01-browse-light.png"><img src="docs/screenshots/01-browse.png" width="220" alt="Browse" /></picture></td>
<td align="center"><b>Fiction detail</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/02-detail.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/02-detail-light.png"><img src="docs/screenshots/02-detail.png" width="220" alt="Fiction detail" /></picture></td>
<td align="center"><b>Reader / audiobook</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/03-reader.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/03-reader-light.png"><img src="docs/screenshots/03-reader.png" width="220" alt="Reader / audiobook" /></picture></td>
</tr>
<tr>
<td align="center"><b>Library</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/04-library.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/04-library-light.png"><img src="docs/screenshots/04-library.png" width="220" alt="Library" /></picture></td>
<td align="center"><b>Follows</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/07-follows.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/07-follows-light.png"><img src="docs/screenshots/07-follows.png" width="220" alt="Follows" /></picture></td>
<td align="center"><b>Settings</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/05-settings.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/05-settings-light.png"><img src="docs/screenshots/05-settings.png" width="220" alt="Settings" /></picture></td>
</tr>
<tr>
<td align="center"><b>Royal Road filters</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/06-filter-dark.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/06-filter.png"><img src="docs/screenshots/06-filter-dark.png" width="220" alt="Royal Road filters" /></picture></td>
<td align="center"><b>GitHub filters</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/06b-filter-github-dark.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/06b-filter-github.png"><img src="docs/screenshots/06b-filter-github-dark.png" width="220" alt="GitHub filters" /></picture></td>
<td></td>
</tr>
</table>

## TTS engine

storyvox links a local TTS engine in-process via the [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack). That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper and Kokoro wrappers into a single dependency. We bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. No second APK, no install gate, no engine-binding handshake — synthesis runs in storyvox's own process.

For users who want studio-grade narration on slow devices, **Azure Cognitive Services HD voices** are wired in as an optional remote backend (BYOK). Add your key and region in **Settings → Voice & Playback → Azure** and pick from the full Azure HD voice roster. If your key fails or the network drops, storyvox falls back to your selected local voice for the rest of the chapter — playback never just stops on you.

Voice model weights for the local engine are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release; the in-app picker shows what's installed and what's available. See [`docs/VOICES.md`](docs/VOICES.md) for the catalog and refresh workflow.

## Install (sideload)

storyvox is currently distributed by sideloading. CI builds debug APKs on every `main` push; tagged releases (`v0.x.x`) attach a signed APK to the GitHub release.

1. Download the latest `storyvox.apk` from the [Releases page](https://github.com/techempower-org/storyvox/releases).
2. On your Android device, enable **Install unknown apps** for whatever browser/file manager you used.
3. Open the APK to install.
4. Launch storyvox. You'll be asked for notification permission (used for the lock-screen tile during playback). The voice picker appears on first launch — pick a Piper voice for a quick first chapter (~14–30 MB) or Kokoro for the multi-speaker model (~330 MB).

System requirements:

- Android 8.0 (API 26) or higher
- ~50 MB free storage for the APK; voice models add 14 MB to ~330 MB
- An internet connection for browsing, chapter download, and the first-time voice download (chapters and voices cache locally)

## Optional: sign in to Royal Road

Anonymous browsing works for all public chapters. **Sign in** unlocks:

- Premium chapters (Patreon-tier early access)
- Your Follows tab — your bookmarked fictions sync down

storyvox uses an in-app WebView for the login flow. Your password never touches our code; only the session cookies are captured (and stored encrypted on-device).

## Build from source

Requires JDK 17, Android SDK 35, and a system gradle ≥ 8.10 for the wrapper bootstrap.

```sh
git clone https://github.com/techempower-org/storyvox.git
cd storyvox

# One-time bootstrap
gradle wrapper --gradle-version 8.10 --distribution-type bin
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build
./gradlew :app:assembleDebug          # phone APK
./gradlew :wear:assembleDebug         # wear APK
./gradlew :app:installDebug           # install on connected device
```

The CI workflow (`.github/workflows/android.yml`) shows the canonical build steps.

## Architecture

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
│ :source-royalroad    │  Cloudflare-aware fetch, login WebView
│ :source-github       │  GitHub API + book.toml + commonmark
│ :source-rss          │  RSS / Atom + storyvox-feeds registry
│ :source-epub         │  SAF folder picker + OPF parser
│ :source-outline      │  Outline wiki API
│ :source-mempalace    │  LAN-only MemPalace daemon
│                      │
│ TTS backends         │
│ ──────────────────── │
│ :source-azure        │  Azure Cognitive Services HD (BYOK, remote)
└──────────────────────┘
                         ▼
                   (audio out)
```

Thirteen Gradle modules. Fiction sources and TTS backends both implement small interfaces declared in `:core-data` / `:core-playback` and bind into `Map<String, …>` registries via Hilt `@IntoMap @StringKey`. The playback layer is independent of the UI; the local engine library is a single transitive dep on `:core-playback`. AI chat lives in its own `:core-llm` module so the TTS app doesn't drag in HTTP clients for providers it isn't using at runtime.

Design specs (each shipped or in flight) read as a thread:

- [Storyvox baseline](docs/superpowers/specs/2026-05-05-storyvox-design.md) — original architecture
- [GitHub source](docs/superpowers/specs/2026-05-06-github-source-design.md) — second fiction source
- [PCM cache](docs/superpowers/specs/2026-05-07-pcm-cache-design.md) — render-to-disk for slow-voice gapless playback
- [VoxSherpa knobs](docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md) — engine settings catalog
- [Settings redesign](docs/superpowers/specs/2026-05-08-settings-redesign-design.md) — grouped-card structure
- [Azure HD voices](docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md) — BYOK cloud TTS
- [GitHub OAuth](docs/superpowers/specs/2026-05-08-github-oauth-design.md) — your private repos as fictions

Per-dreamer detail specs live in `scratch/dreamers/`.

## Stack

| | |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose, Material 3 |
| DI | Hilt (KSP) |
| Storage | Room, DataStore Preferences, EncryptedSharedPreferences |
| Networking | OkHttp + Jsoup (RR is HTML, not JSON) + commonmark (GitHub markdown) |
| Playback | Media3 SimpleBasePlayer + custom AudioTrack pipeline + Tier 3 multi-engine producer |
| TTS (local) | VoxSherpa-TTS engine-lib (Piper + Kokoro on sherpa-onnx) — in-process |
| TTS (cloud, optional) | Azure Cognitive Services HD voices (BYOK) with offline fallback |
| Async | Coroutines + Flow |
| Wear OS | Compose for Wear, `play-services-wearable` |
| CI | GitHub Actions |

## Roadmap

The v0.4 line shipped 80+ point releases — the engine, six fiction sources, AI chat, OAuth, the Settings redesign, Azure HD as a remote TTS option, and the Tier 3 perf lane are all in. The next wave is the v0.5 line: better recall, more shaping of the read-aloud, and the long-promised knowledge graph.

**Shipped in v0.4 (since v0.4.55):**
- **Azure HD voices (BYOK).** Optional cloud TTS via Azure Cognitive Services. Settings UI ([#182](https://github.com/techempower-org/storyvox/issues/182)), engine wiring ([#183](https://github.com/techempower-org/storyvox/issues/183)), error handling and retries ([#184](https://github.com/techempower-org/storyvox/issues/184)), offline fallback ([#185](https://github.com/techempower-org/storyvox/issues/185)), full voice roster + cache eviction priority ([#186](https://github.com/techempower-org/storyvox/issues/186)). Bring your own key — never billed by storyvox.
- **Tier 3 multi-engine parallel synthesis.** Twin sliders for Engines × Threads/engine in Settings → Performance, producer pinned to a dedicated `URGENT_AUDIO` thread, VoxSherpa multi-core synced with upstream main.
- **EPUB import** ([#235](https://github.com/techempower-org/storyvox/issues/235)). Folder picker via Storage Access Framework + an OPF parser; any folder of EPUB files becomes a Browse tab.
- **RSS / Atom feeds** ([#236](https://github.com/techempower-org/storyvox/issues/236)). Subscribe to any feed; suggested feeds curated in [storyvox-feeds](https://github.com/techempower-org/storyvox-feeds) ([#246](https://github.com/techempower-org/storyvox/issues/246)).
- **Outline self-hosted-wiki backend** ([#245](https://github.com/techempower-org/storyvox/issues/245)). Treat your Outline collections as fictions, articles as chapters.
- **Smart-resume CTA.** Library Resume button respects last paused/playing intent — no more surprise auto-play.
- **AI Sessions surface** ([#218](https://github.com/techempower-org/storyvox/issues/218)) — Settings → AI → Sessions to review past chats.
- **AI read-aloud per assistant turn** ([#214](https://github.com/techempower-org/storyvox/issues/214)) — speak any chat reply through the same TTS pipeline.
- **Per-voice speed and pitch defaults** ([#195](https://github.com/techempower-org/storyvox/issues/195)).
- **Punctuation cadence drives Kokoro `silence_scale`** ([#196](https://github.com/techempower-org/storyvox/issues/196)) — one slider, two engines, consistent feel.
- **Stable debug-keystore signing** — clean upgrades over older debug builds without uninstall.

**v0.5 candidates:**
- **Notion as a seventh fiction backend** ([#233](https://github.com/techempower-org/storyvox/issues/233)).
- **Knowledge graph for fiction.** Per-book Notebook (characters, places, who-said-what) seeding into MemPalace ([#147](https://github.com/techempower-org/storyvox/issues/147)).
- **VoxSherpa knob exposure.** Loudness normalization, breath pause, pitch envelope as user-tunable settings ([research draft](docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md)).
- **PCM cache PRs C–H.** Auto-population, settings UI for cache size, graceful fallback ([#86](https://github.com/techempower-org/storyvox/issues/86)).

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the long-form roadmap and backlog.

## How it was built

storyvox was built starting May 5, 2026 by JP Hein orchestrating teams of dream-named [Claude Opus](https://www.anthropic.com/claude) agents working in parallel via [Claude Code](https://www.anthropic.com/claude-code). The original five-act structure shipped the v0.3 line:

1. **storyvox-dreamers** — Morpheus, Selene, Oneiros, Hypnos, Aurora drafted the architecture, data layer, Royal Road integration, playback engine, and design system.
2. **storyvox-tonight** — Phantasos, Morrigan, Caelus wired live audio playback, library round-trip, and loading skeletons.
3. **storyvox-finish** — Iris, Aether, Pheme, Janus added the sentence-highlight reader, player polish (speed/sleep/voice), MediaStyle notification, and CI/CD.
4. **storyvox-features-2** — Athena, Themis, Hestia shipped sign-in, full RR filters, and responsive grids.
5. **Davis** orchestrated all four teams, integrated their work, verified on a Galaxy Tab A7 Lite, committed and pushed.

The v0.4 line landed the in-process VoxSherpa engine, voice library, GitHub fiction source, infinite-scroll browse, motion polish, and the PCM cache filesystem layer. Aurelia owned the perf lane and benched Piper-high "cori" at 0.285× realtime on the target device — the number that justifies the cache work. Bryn shipped Performance Mode A/B toggles. The voice picker grew tiers, stars, and a Starred surface.

The May 8 round was the largest single-day landing yet — 30+ agents in parallel under JP's orchestration. Indigo specced the Settings redesign; Saga implemented it. Solara specced Azure HD; Ember specced GitHub OAuth; Thalia catalogued every VoxSherpa knob worth exposing. Iris refreshed the README and clarified the GPL-3.0 license posture (downstream obligation, not branding). Calliope chased an auth-cookie race. Briar polished the README you're reading.

Through the v0.4.56 → v0.4.83 stretch the dream-team kept landing: Solara's Azure work shipped end-to-end (BYOK Settings → engine wiring → retries → offline fallback → roster + eviction priority); Reeve and Lyra opened the source surface to RSS, EPUB, and Outline; Aurelia cut Tier 3 multi-engine parallel synthesis with twin Engines/Threads sliders; Hazel landed the smart-resume CTA so the Library button stops auto-playing at you. The dream-team retro reads as a thread of small, named contributions — each commit message preserves who did what, and `git log` reads as the credits.

## License

storyvox is licensed under the [GNU General Public License v3.0](LICENSE).

We statically link [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) (GPL-3.0) into the APK as our TTS engine. The combined work is therefore GPL-3.0 — this license is not a posture choice, it's a downstream obligation. Relicensing more permissively would require replacing the engine, not just changing this file.

You're free to use, modify, and redistribute under the terms of the GPL-3.0.
