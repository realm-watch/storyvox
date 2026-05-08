# storyvox

[![Build](https://github.com/jphein/storyvox/actions/workflows/android.yml/badge.svg)](https://github.com/jphein/storyvox/actions/workflows/android.yml)

**A beautiful, neural-voice audiobook player for serial fiction.**
Stream chapters from [Royal Road](https://royalroad.com) and [GitHub](https://github.com/) read aloud by an offline neural TTS engine, with a hybrid reader/audiobook view that highlights the spoken sentence as you listen. Built for Android phones, tablets, and Wear OS.

<p align="center">
  <img src="docs/screenshots/03-reader.png" width="320" alt="storyvox reader playing The Archmage Coefficient" />
</p>

---

## What it does

- **Two fiction sources, side by side.** Browse [Royal Road](https://royalroad.com) with the full filter set (tags include/exclude, status, type, length, rating, content warnings, sort order) or browse fiction repositories on GitHub via the curated [storyvox-registry](https://github.com/jphein/storyvox-registry) plus live `/search/repositories` results.
- **Plays chapters as audiobooks** through an in-process neural TTS engine. Two voice families ship: [Piper](https://github.com/rhasspy/piper) (compact, fast, ~14–30 MB per voice) and [Kokoro](https://github.com/hexgrad/kokoro) (multi-speaker, ~330 MB). Voice models download on demand from the project's `voices-v2` release; nothing is bundled in the APK. No cloud, no API keys, no per-character billing.
- **Highlights the current sentence** in brass on the reader view as the TTS speaks. Swipe between audiobook view (cover + scrubber + transport) and reader view (chapter text). The highlight glides between sentences to match the read-aloud rhythm.
- **Auto-advances** between chapters. Eager-downloads ahead so the next chapter is ready when the current one ends. PCM cache buffering keeps playback smooth even when synthesis temporarily falls behind — the player pauses, refills the buffer, and resumes without a glitch.
- **Sleep timer** with 15/30/45/60 minute presets, an "end of chapter" mode, and a countdown pulse as time runs out.
- **Library + Follows tabs** with sign-in via WebView (your Royal Road follow list syncs into the app).
- **Infinite-scroll Browse** across every tab.
- **Cheap polling for new chapters.** GitHub-sourced fictions watch the repo's HEAD commit SHA and only re-scan the manifest when something changes — so checking for updates is one HTTP request per fiction.
- **MediaSession-aware** — lock screen art, transport from Bluetooth headsets, headphone media buttons, notification shade.
- **Library Nocturne theme** — brass on warm dark, EB Garamond chapter body, Inter UI. Light mode is parchment cream.
- **Adaptive layouts** — fills the screen on phones (2 columns), tablets (5 columns), foldables (more).

## Screens

<table>
<tr>
<td align="center"><b>Browse</b><br/><img src="docs/screenshots/01-browse.png" width="220" /></td>
<td align="center"><b>Fiction detail</b><br/><img src="docs/screenshots/02-detail.png" width="220" /></td>
<td align="center"><b>Reader / audiobook</b><br/><img src="docs/screenshots/03-reader.png" width="220" /></td>
</tr>
<tr>
<td align="center"><b>Library</b><br/><img src="docs/screenshots/04-library.png" width="220" /></td>
<td align="center"><b>Settings</b><br/><img src="docs/screenshots/05-settings.png" width="220" /></td>
<td align="center"><b>Filters</b><br/><img src="docs/screenshots/06-filter.png" width="220" /></td>
</tr>
</table>

## TTS engine

storyvox links the TTS engine in-process via the [VoxSherpa-TTS](https://github.com/jphein/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack). That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper and Kokoro engine wrappers into a single dependency. We bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. No second APK, no install gate, no engine-binding handshake — synthesis runs in storyvox's own process.

Voice model weights are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release; the in-app voice picker shows what's installed and what's available. See [`docs/VOICES.md`](docs/VOICES.md) for the catalog and refresh workflow.

## Install (sideload)

storyvox is currently distributed by sideloading. The CI builds debug APKs on every `main` push; tagged releases (`v0.x.x`) attach a signed APK to the GitHub release.

1. Download the latest `storyvox.apk` from the [Releases page](https://github.com/jphein/storyvox/releases).
2. On your Android device, enable **Install unknown apps** for whatever browser/file manager you used.
3. Open the APK to install.
4. Launch storyvox. You'll be asked for notification permission (used for the lock-screen tile during playback). The voice picker appears on first launch — pick a Piper voice for a quick first chapter (~14–30 MB) or Kokoro for the multi-speaker model (~330 MB).

System requirements:

- Android 8.0 (API 26) or higher
- ~50 MB free storage for the APK; voice models add 14 MB to ~330 MB depending on which you install
- An internet connection for browsing, chapter download, and the first-time voice download (chapters and voices are cached locally)

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

Eight Gradle modules. Sources implement an interface declared in `:core-data` and are bound into `Map<String, FictionSource>` via Hilt `@IntoMap @StringKey`. The playback layer is independent of UI; the engine library is a single transitive dep on `:core-playback`.

For more depth see [`docs/superpowers/specs/2026-05-05-storyvox-design.md`](docs/superpowers/specs/2026-05-05-storyvox-design.md), [`docs/superpowers/specs/2026-05-06-github-source-design.md`](docs/superpowers/specs/2026-05-06-github-source-design.md), and the per-dreamer detail specs in `scratch/dreamers/`.

## Stack

| | |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose, Material 3 |
| DI | Hilt (KSP) |
| Storage | Room, DataStore Preferences, EncryptedSharedPreferences |
| Networking | OkHttp + Jsoup (RR is HTML, not JSON) + commonmark (GitHub markdown) |
| Playback | Media3 SimpleBasePlayer + custom AudioTrack pipeline |
| TTS | VoxSherpa-TTS engine-lib (Piper + Kokoro on sherpa-onnx) — in-process |
| Async | Coroutines + Flow |
| Wear OS | Compose for Wear, `play-services-wearable` |
| CI | GitHub Actions |

## How it was built

storyvox was built starting May 5, 2026 by JP Hein orchestrating teams of dream-named [Claude Opus](https://www.anthropic.com/claude) agents working in parallel via [Claude Code](https://www.anthropic.com/claude-code). The original five-act structure shipped the v0.3 line:

1. **storyvox-dreamers** — Morpheus, Selene, Oneiros, Hypnos, Aurora drafted the architecture, data layer, Royal Road integration, playback engine, and design system.
2. **storyvox-tonight** — Phantasos, Morrigan, Caelus wired live audio playback, library round-trip, and loading skeletons.
3. **storyvox-finish** — Iris, Aether, Pheme, Janus added sentence-highlight reader, player polish (speed/sleep/voice), MediaStyle notification, and CI/CD.
4. **storyvox-features-2** — Athena, Themis, Hestia shipped sign-in, full RR filters, and responsive grids.
5. **Davis** orchestrated all four teams, integrated their work, verified on a Galaxy Tab A7 Lite, committed and pushed.

Subsequent dreamer teams have shipped the in-process TTS engine, voice library, GitHub fiction source, infinite-scroll browse, motion polish, and PCM cache buffering. Each commit message preserves who did what — `git log` reads as a team retro.

## License

storyvox is licensed under the [GNU General Public License v3.0](LICENSE).

We statically link [VoxSherpa-TTS](https://github.com/jphein/VoxSherpa-TTS) (GPL-3.0) into the APK as our TTS engine. The combined work is therefore GPL-3.0 — this license is not a posture choice, it's a downstream obligation. Relicensing more permissively would require replacing the engine, not just changing this file.

You're free to use, modify, and redistribute under the terms of the GPL-3.0.
