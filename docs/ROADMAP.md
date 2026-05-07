# storyvox roadmap

A short-form list of in-flight + planned work. Detailed designs live under
`docs/superpowers/specs/`.

## v0.4.0 — in-process VoxSherpa engine (in flight)

Bypass Android's `TextToSpeech` framework entirely; storyvox links the
VoxSherpa engine in-process via JitPack and manages its own AudioTrack with
a fat buffer. Eliminates per-sentence framework dispatch + small-buffer
audio underrun (the gappy-playback problem).

- [x] VoxSherpa fork: add `:engine-lib` module that re-projects the engine
      classes via Gradle `sourceSets` (no file moves → upstream merges
      stay clean) → publishes as JitPack AAR
- [ ] storyvox: `EnginePlayer` extends `SimpleBasePlayer`, calls
      `VoiceEngine.getInstance().generateAudioPCM(...)` directly, manages
      own `AudioTrack` with ~2s buffer, pipelines next-sentence generation
- [ ] First-launch model download: pull a small Piper voice (Amy low ~14MB)
      from huggingface into app private storage
- [ ] Drop install gate, FileProvider, REQUEST_INSTALL_PACKAGES, fork URL
      pin in BuildConfig — all the cruft from the AIDL/separate-APK era
- [ ] Bump to v0.4.0 with release narrative: "self-contained audiobook
      player, no extra apps required"

## Next: GitHub as a second fiction source

Spec: [`docs/superpowers/specs/2026-05-06-github-source-design.md`](superpowers/specs/2026-05-06-github-source-design.md)

The source layer is already pluggable behind `FictionSource`. Adding GitHub
is the test of that abstraction. Highlights:

- **Multi-source Hilt refactor.** `Map<String, FictionSource>` with
  `@IntoMap @StringKey` keyed by `sourceId`. `:source-royalroad` and
  `:source-github` plug in side by side. No user-visible change.
- **Paste-anything URL flow.** Library `+` FAB opens a sheet that accepts
  Royal Road URLs *and* GitHub URLs — `UrlRouter` regex-routes by pattern.
- **`:source-github` module.** OkHttp client → GitHub API,
  `book.toml` (mdbook) + `storyvox.json` manifest parsing,
  bare-repo fallback for unmarked-up repos. Markdown → HTML/plaintext via
  commonmark.
- **Curated registry.** `jphein/storyvox-registry` repo holds
  `registry.json`. Storyvox fetches once per session, surfaces entries as
  the **Featured** row at the top of Browse → GitHub.
- **Live search + filters.** `/search/repositories?q=topic:fiction+...` for
  the rest of the screen. Filter sheet mirrors the Royal Road one (tags,
  status, length, recency, stars, language). Filter quality scales with
  manifest adoption — bare repos still filter on what GitHub itself
  exposes.
- **Auth (deferred).** Ship v1 unauthenticated (60 req/hr is enough with
  caching). Optional GitHub PAT field in Settings → Sources lands later.
- **Sync.** Commit-SHA-based polling; `ChapterDownloadWorker` reused — just
  hits raw.githubusercontent.com instead of royalroad.com.

Build sequence (each step ships value on its own):

1. Multi-source Hilt refactor (no UX change).
2. UrlRouter + paste-anything sheet (RR-only at first).
3. `:source-github` module + GitHubApi client.
4. Manifest parsing.
5. Markdown → HTML/plaintext rendering.
6. Add-by-URL routes GitHub end-to-end.
7. Browse → GitHub: registry-only Featured row.
8. Browse → GitHub: search + filter sheet.
9. Commit-SHA polling for new chapters.

## Backlog

- **Try `AudioTrack.Builder` + `AudioAttributes`**: `EnginePlayer.create-`
  `AudioTrack` currently uses the six-arg `AudioTrack(STREAM_MUSIC, …)` ctor
  to match VoxSherpa standalone exactly while we hunted issue #6. The
  Builder form is the modern API and would let us set `USAGE_MEDIA` /
  `CONTENT_TYPE_MUSIC` / `CONTENT_TYPE_SPEECH` cleanly. Once we're confident
  the fuzz is gone on Tab A7 Lite, swap and A/B for any audible difference.
- **Voice picker UI**: pull from a curated list of Piper voices, download on
  demand, cache in app storage. Replaces the placeholder "use whatever
  VoxSherpa happened to install" model.
- **Wear OS playback controls**: scaffold exists in `:wear` but isn't
  wired. Pick up after engine refactor settles.
- **Auto integration**: Media3 `MediaSessionService` + `MediaBrowserService`
  exposes the library to Android Auto. Wear-of-the-spec is in the original
  design doc.
- **Voice-tagging in `storyvox.json`**: lets a fiction author specify a
  preferred narrator voice (`narrator: "en-US-Andrew"`). Per-fiction
  default, user can still override.
- **Sleep timer end-of-chapter mode**: end-of-chapter fade-out
  implemented but the duration-based timer needs a fade-out tail too.
- **ePub / PDF / Gutenberg ingest**: separate source modules.
  Out-of-scope until GitHub source proves the abstraction.
