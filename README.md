# storyvox

![Build](https://github.com/jphein/storyvox/actions/workflows/android.yml/badge.svg)

Android audiobook-style player for Royal Road web fiction. Hybrid reader + audiobook with sentence-level highlighting, eager downloads, auto-advance, polling, Bluetooth deep controls, Android Auto, and a Wear OS companion. Routes TTS through a forked VoxSherpa engine via `setEngineByPackageName`.

**Status**: scaffold landing. v1 implementation in flight by the storyvox-dreamers team.

## Design spec

The full design lives in `docs/superpowers/specs/2026-05-05-storyvox-design.md` (assembled from the `scratch/dreamers/*.md` per-author sections). High-level decisions are in `scratch/dreamers/CONTEXT.md`.

## Modules

```
:app                phone entrypoint, navigation, DI root
:wear               Wear OS companion
:core-data          Room, FictionSource interface, repos
:core-playback      MediaSessionService, TTS client, Auto MediaBrowser
:core-ui            Library Nocturne theme, design system
:source-royalroad   Royal Road FictionSource implementation
:feature            library / follows / browse / reader / audiobook
```

## Build

Requires JDK 17+ and Android SDK 35.

```sh
# one-time: generate the wrapper
gradle wrapper --gradle-version 8.10 --distribution-type bin

# then
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

`local.properties` should set `sdk.dir=` to your Android SDK location.

## License

See `LICENSE` — placeholder; a real license will be applied before public release.
