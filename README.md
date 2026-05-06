# storyvox

[![Build](https://github.com/jphein/storyvox/actions/workflows/android.yml/badge.svg)](https://github.com/jphein/storyvox/actions/workflows/android.yml)

**A beautiful, neural-voice audiobook player for [Royal Road](https://royalroad.com).**
Stream serial fiction read aloud by an offline neural TTS engine, with a hybrid reader/audiobook view that highlights the spoken sentence as you listen. Built for Android phones, tablets, and Wear OS.

<p align="center">
  <img src="docs/screenshots/03-reader.png" width="320" alt="storyvox reader playing The Archmage Coefficient" />
</p>

---

## What it does

- **Browses Royal Road** with the full filter set: tags include/exclude, status, type, length, rating, content warnings, sort order.
- **Plays chapters as audiobooks** through [VoxSherpa](https://github.com/CodeBySonu95/VoxSherpa-TTS) — a fully offline neural TTS engine that runs locally on your device. No cloud, no API keys, no per-character billing.
- **Highlights the current sentence** in brass on the reader view as the TTS speaks. Swipe between audiobook view (cover + scrubber + transport) and reader view (chapter text).
- **Auto-advances** between chapters. Eager-downloads ahead so the next chapter is ready when the current one ends.
- **Sleep timer** with 15/30/45/60 minute presets and an "end of chapter" mode.
- **Library + Follows tabs** with sign-in via WebView (your Royal Road follow list syncs into the app).
- **MediaSession-aware** — lock screen art, transport from Bluetooth headsets, headphone media buttons, notification shade.
- **Library Nocturne theme** — brass on warm dark, EB Garamond chapter body, Inter UI. Light mode is parchment cream.
- **Adaptive layouts** — fills the screen on phones (2 columns), tablets (5 columns), foldables (more).

## Screens

<table>
<tr>
<td align="center"><b>Browse Royal Road</b><br/><img src="docs/screenshots/01-browse.png" width="220" /></td>
<td align="center"><b>Fiction detail</b><br/><img src="docs/screenshots/02-detail.png" width="220" /></td>
<td align="center"><b>Reader / audiobook</b><br/><img src="docs/screenshots/03-reader.png" width="220" /></td>
</tr>
<tr>
<td align="center"><b>Library</b><br/><img src="docs/screenshots/04-library.png" width="220" /></td>
<td align="center"><b>Settings</b><br/><img src="docs/screenshots/05-settings.png" width="220" /></td>
<td align="center"><b>Filters</b><br/><img src="docs/screenshots/06-filter.png" width="220" /></td>
</tr>
</table>

## Why VoxSherpa

storyvox is a *consumer* of a system Text-to-Speech engine, not the engine itself. We picked [VoxSherpa](https://github.com/CodeBySonu95/VoxSherpa-TTS) (CodeBySonu95) because:

- **It's offline** — neural inference runs on your phone, your reading habits never leave the device.
- **It sounds good** — neural voices comparable to commercial cloud TTS.
- **It's a system-wide engine** — registers with Android's `TextToSpeech` framework, so storyvox just calls `setEngineByPackageName("com.CodeBySonu.VoxSherpa")` and the OS handles the rest.

VoxSherpa is GPL-3.0 and ships as a separate APK. **storyvox depends on having it installed** — first launch will surface a prompt to install if not present. You can fall back to your system default TTS engine, but the audio quality is noticeably lower.

## Install (sideload)

storyvox is currently distributed by sideloading. The CI builds debug APKs on every `main` push; tagged releases (`v0.x.x`) attach a signed APK to the GitHub release.

1. Download the latest `storyvox-debug.apk` from the [Releases page](https://github.com/jphein/storyvox/releases).
2. Install [VoxSherpa-TTS](https://github.com/CodeBySonu95/VoxSherpa-TTS/releases) (separate APK).
3. On your Android device, enable **Install unknown apps** for whatever browser/file manager you used.
4. Open the APK to install.
5. Launch storyvox. You'll be asked for notification permission (used for the lock-screen tile during playback).

System requirements:

- Android 8.0 (API 26) or higher
- ~50 MB free storage for storyvox; VoxSherpa adds ~500 MB for the bundled neural voice
- An internet connection for browsing and chapter download (chapters are cached locally)

## Optional: sign in to Royal Road

Anonymous browsing works for all public chapters. **Sign in** unlocks:

- Premium chapters (Patreon-tier early access)
- Your Follows tab — your bookmarked fictions sync down

storyvox uses an in-app WebView for the login flow. Your password never touches our code; only the session cookies are captured (and stored encrypted on-device).

## Build from source

Requires JDK 17, Android SDK 35, and a system gradle ≥ 8.10 for the wrapper bootstrap.

```sh
git clone https://github.com/jphein/storyvox.git
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
       │       │  Detail / Settings   │
       │       └──────┬─────┬─────────┘
       │              │     │
       ▼              ▼     ▼
┌────────────┐  ┌─────────────────┐  ┌───────────────┐
│ :core-data │  │ :core-playback  │  │  :core-ui     │
│  Room +    │  │  MediaSession + │  │  Library      │
│  repos +   │  │  TtsPlayer +    │  │  Nocturne     │
│  Fiction   │  │  SentenceTracker│  │  theme +      │
│  Source    │  │  + Wear bridge  │  │  components   │
└─────┬──────┘  └─────────────────┘  └───────────────┘
      │
      ▼
┌──────────────────────────┐
│ :source-royalroad        │
│  Cloudflare-aware fetch, │
│  parsers, login WebView, │
│  honeypot filter         │
└──────────────────────────┘
```

Eight Gradle modules. The dependency arrows are deliberate — `:core-ui` is a leaf, sources implement an interface declared in `:core-data`, the playback layer is independent of UI.

For more depth see [`docs/superpowers/specs/2026-05-05-storyvox-design.md`](docs/superpowers/specs/2026-05-05-storyvox-design.md) and the per-dreamer detail specs in `scratch/dreamers/`.

## Stack

| | |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose, Material 3 |
| DI | Hilt (KSP) |
| Storage | Room, DataStore Preferences, EncryptedSharedPreferences |
| Networking | OkHttp + Jsoup (RR is HTML, not JSON) |
| Playback | Media3 SimpleBasePlayer + Android `TextToSpeech` |
| Async | Coroutines + Flow |
| Wear OS | Compose for Wear, `play-services-wearable` |
| CI | GitHub Actions |

## How it was built

storyvox was built in a single day (May 5, 2026) by JP Hein orchestrating teams of dream-named [Claude Opus](https://www.anthropic.com/claude) agents working in parallel via [Claude Code](https://www.anthropic.com/claude-code). The five-act structure:

1. **storyvox-dreamers** — Morpheus, Selene, Oneiros, Hypnos, Aurora drafted the architecture, data layer, Royal Road integration, playback engine, and design system.
2. **storyvox-tonight** — Phantasos, Morrigan, Caelus wired live audio playback, library round-trip, and loading skeletons.
3. **storyvox-finish** — Iris, Aether, Pheme, Janus added sentence-highlight reader, player polish (speed/sleep/voice), MediaStyle notification, and CI/CD.
4. **storyvox-features-2** — Athena, Themis, Hestia shipped sign-in, full RR filters, and responsive grids.
5. **Davis** orchestrated all four teams, integrated their work, verified on a Galaxy Tab A7 Lite, committed and pushed.

Each commit message preserves who did what — `git log` reads as a team retro.

## License

`LICENSE` is currently a placeholder. A proper open-source license will be applied before public release.
