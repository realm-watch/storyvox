---
layout: default
title: Install storyvox
description: Sideload storyvox on Android. System requirements, voice download sizes, and the optional Royal Road sign-in.
---

# Install

storyvox is currently distributed by sideloading. CI builds debug APKs on every push to `main`; tagged releases (`v0.x.x`) attach a signed APK to the corresponding GitHub release.

## Quick install

1. Open the [Releases page](https://github.com/techempower-org/storyvox/releases) on your Android device.
2. Download the latest `storyvox-vX.Y.Z.apk` from the assets.
3. On the device, enable **Install unknown apps** for whichever browser or file manager you used.
4. Open the APK to install.
5. Launch storyvox. You'll be asked for **notification permission** (used for the lock-screen tile during playback).
6. The voice picker appears on first launch — pick a Piper voice for a quick first chapter (~14–30 MB) or Kokoro if you want the multi-speaker model (~330 MB).

That's it. There's no account requirement. Anonymous browsing works for all public Royal Road fictions and every GitHub-sourced fiction.

## System requirements

| Requirement | Minimum |
|---|---|
| Android version | 8.0 (API 26) |
| Free storage (APK only) | ~140 MB |
| Free storage (APK + one Piper voice) | ~155 MB |
| Free storage (APK + KittenTTS) | ~165 MB |
| Free storage (APK + Kokoro) | ~470 MB |
| RAM | 2 GB+ recommended; tested on 3 GB Tab A7 Lite |
| Network | Wi-Fi for first launch and voice download; chapters cache locally |

The TTS engine runs in storyvox's own process via the
[VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) `:engine-lib` AAR, so there's no second
APK to install and no engine-binding handshake. Voice models download on demand from the project's
[`voices-v2`](https://github.com/techempower-org/VoxSherpa-TTS/releases/tag/voices-v2) GitHub release.
Nothing is bundled in the APK.

## Optional: sign in to Royal Road

Anonymous browsing works for every public chapter. **Sign in** unlocks:

- **Premium chapters** — Patreon-tier early access on supported fictions.
- **Your Follows tab** — your bookmarked Royal Road fictions sync down into storyvox.

storyvox uses an in-app WebView for the login flow. **Your password never touches our code; only the session cookies are captured, and they're stored encrypted on-device** via Android's `EncryptedSharedPreferences`.

To sign in:

1. Open the **Follows** tab.
2. Tap **Sign in**.
3. Complete the Royal Road login form in the WebView.
4. Return to the app — your follow list will sync within a few seconds.

To sign out: **Settings → Account → Sign out**.

## Build from source

Requires JDK 17, Android SDK 35, and a system Gradle ≥ 8.10 for the wrapper bootstrap.

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

The CI workflow `.github/workflows/android.yml` is the canonical build path.

## What about Wear OS?

There is a `:wear` module that builds an APK companion. Pairing-and-discovery via
`play-services-wearable` works in principle, but it isn't yet a polished experience and isn't
recommended for daily use. If you want to experiment, sideload the wear APK from the same release.

## Update path

storyvox doesn't auto-update. When a new release ships:

1. Download the new APK from [Releases](https://github.com/techempower-org/storyvox/releases).
2. Open it. Android will prompt to upgrade in place — your library, voices, and progress are preserved.

The app's signing key has been stable since `v0.4.15` (a checked-in debug keystore — see
[#15](https://github.com/techempower-org/storyvox/pull/15)). Releases before that used the throwaway
debug keystore Gradle generates per machine, so an upgrade from those earlier builds may
require a clean uninstall first. Installs from `v0.4.15` forward upgrade in place.
