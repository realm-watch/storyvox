# Changelog

All notable changes to storyvox land here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
the `versionName` in `app/build.gradle.kts` and the `v*` git tag.

Entries before v0.5.12 are reconstructed from the git log — see
`git log --oneline` for the exhaustive record.

## [Unreleased]

## [0.5.21] — 2026-05-13

### Infrastructure
- **Repo moved from `realm-watch` to `techempower-org`** — second transfer of the day. realm-watch was originally framed around homelab theming; `techempower-org` is JP's company org and the more permanent home for storyvox and its product-line siblings. Eight repos transferred together: storyvox, storyvox-feeds, VoxSherpa-TTS, forageforall, techempower.org (the website), mempalace, palace-daemon, multipass-structural-memory-eval. realm-watch stays alive for future homelab projects.
- Owners / Maintainers / Contributors teams replicated on techempower-org with the same admin / maintain / push permissions; all eight repos team-bound. CODEOWNERS team mentions updated to `@techempower-org/*`. Branch protection on `main` re-applied (CODEOWNERS review + green CI + no force-push + no deletion + conversation resolution required).
- VoxSherpa-TTS JitPack coordinate: `com.github.techempower-org:VoxSherpa-TTS:v2.7.13` (was `com.github.realm-watch:...`, was `com.github.jphein:...`). Verified the new coordinate builds with `--refresh-dependencies` before flipping.
- SIGIL_REPO updated; CLAUDE.md memories swept of `realm-watch/storyvox` references.

## [0.5.20] — 2026-05-13

### Added
- **Audio-stream backend category** (#389, closes #373) —
  `ChapterContent` gains optional `audioUrl: String?`. When non-null
  the playback engine bypasses the TTS pipeline and routes the URL
  through a sibling Media3 ExoPlayer instance; when null (every
  existing backend) the TTS path is unchanged. Schema migration
  v6→v7 adds the `audioUrl` column so live-stream URLs persist
  across reboots. Pitch slider hides on live audio (Sonic
  pitch-shifting applies to engine-rendered PCM, not network audio).
- **KVMR community radio** (#389, closes #374) — first concrete
  entry in the audio-stream category. JP's local station; single
  live fiction whose one chapter (`Live`) carries the AAC stream
  URL from KVMR's public listen-live page. Defaults ON. Browse →
  KVMR → Live; lockscreen MediaSession surfaces "KVMR Community
  Radio" with transport controls.

### Changed
- **FictionDetail Follow button generalized** (#388, closes #382) —
  the hardcoded `sourceId == "royalroad"` check becomes
  `FictionSource.supportsFollow: Boolean` (default false), plumbed
  through `FictionSummary.supportsFollow` and
  `UiFiction.sourceSupportsFollow`. RoyalRoadSource opts in;
  future AO3 / GitHub-watch / Wikipedia-watchlist / etc backends
  opt in with one line of override.

### Infrastructure
- **Repo transferred from `jphein` to the `realm-watch` org** —
  storyvox + storyvox-feeds + VoxSherpa-TTS all moved. Public on
  the free tier. Branch protection on `main` requires CODEOWNERS
  review + green CI; no force-push, no deletion. Three teams
  (Owners / Maintainers / Contributors) wired with admin / maintain
  / push permissions. CODEOWNERS routes sensitive paths
  (`storyvox-debug.keystore`, `.github/workflows/`, `CLAUDE.md`) to
  Owners and build/release-affecting files to Maintainers. Org-level
  Projects v2 board "storyvox roadmap" with Priority + Area fields.
  VoxSherpa-TTS JitPack coordinate updated to
  `com.github.realm-watch:VoxSherpa-TTS:v2.7.13`.

## [0.5.19] — 2026-05-13

### Added
- **Three new fiction backends** landed in parallel:
  - **Archive of Our Own** (#385, closes #381) — fanfiction via per-tag
    Atom feeds + official EPUB downloads. Zero scraping. Six curated
    fandoms in v1 (Marvel/HP/SW/Original Work/Sherlock/Good Omens).
    Defaults OFF (Explicit-rated possibility).
  - **Standard Ebooks** (#386, closes #375) — curated typographically
    polished public-domain classics. Catalog via SE's public HTML
    listing (schema.org RDFa structured data), content via per-work
    EPUB. Pairs with Gutenberg as the "polished classics" companion.
  - **Wikipedia** (#387, closes #377) — first non-fiction long-form
    backend. Each article = one fiction, each top-level section = one
    chapter. Search via opensearch, Popular = Today's Featured Article
    + mostread cluster. Per-language host configurable in Settings.

### Changed
- **Sonic pitch-interpolation quality toggle** (#372, closes #193) —
  new Settings → Voice & Playback switch *"High-quality pitch
  interpolation"*, defaults ON. Cross-repo with VoxSherpa-TTS v2.7.13
  which parameterized `Sonic.setQuality` via static fields on both
  VoiceEngine and KokoroEngine.

## [0.5.18] — 2026-05-13

### Fixed
- **Gutenberg Browse tap no longer crashes** (#371) —
  `GutendexApi.{request, downloadEpub}` now wrap their OkHttp
  `execute()` calls in `withContext(Dispatchers.IO)`. `suspend`
  alone doesn't move work off the main thread; the previous
  implementation tripped StrictMode's `NetworkOnMainThreadException`
  on the first DNS lookup. Pattern now matches `:source-outline` /
  `:source-rss`.
- **Pick-a-voice picker** (#369) — surfaces three Piper quality
  tiers of Lessac (en_US) plus two of Cori (en_GB), not a mixed
  Cori/Lessac/Aoede grab-bag. Removes Aoede's misleading "1 MB"
  size (Kokoro `sizeBytes = 0` is correct — shared model — but
  rounded nonsensically). Strips stale ⭐ from Lessac/Ryan/Amy
  `displayName` so favorites can own the glyph unambiguously.

### Changed
- **Voices is now a first-class bottom-nav slot** (#370, closes
  #264 nav part) — replaces Settings in the bottom bar (last
  position, `RecordVoiceOver` icon). Settings moves to a gear
  IconButton in every main screen's top bar (Library, Browse,
  Follows, Playing, Voices). Voice-picking is a high-frequency
  activity for an audio-first app, not a set-once preference.
- First-launch default voice changes from `piper_cori_en_GB_high`
  (114 MB) to `piper_lessac_en_US_low` (63 MB) — the smallest of
  the new starter triplet. Users who want richer audio pick
  Medium or High in the picker before the gate dismisses.

## [0.5.17] — 2026-05-13

### Added
- **Follow on Royal Road button on FictionDetail** (#368, closes
  #211) — inline action bar gains a third button next to *Add to
  library* and *Listen*, visible only on RR-sourced fictions.
  Pushes the follow state to RR's account via the existing
  `RoyalRoadSource.setFollowed()` (CSRF + POST to
  `/fictions/setbookmark`). Anonymous tap routes to the same
  `AUTH_WEBVIEW` Browse and Settings already use. Closes the
  two-way sync loop — pull from `/my/follows` was already wired in
  v0.4.x.

### Changed
- `UiFiction` and `FictionSummary` gain `sourceId` and
  `followedRemotely` fields (defaulted to be backward-compatible
  with all existing construction sites).

## [0.5.16] — 2026-05-13

### Changed
- **RSS feed management moves to a Browse FAB** (#367, closes #247) —
  add / list / remove / suggested-feeds all live on a `+ Add feed`
  FAB-launched sheet from Browse → RSS now. The Settings page keeps
  only the on/off toggle (its subtitle points users at the new home).
  Same underlying repository API; only the home screen changed.

## [0.5.15] — 2026-05-13

### Added
- **Project Gutenberg backend** (#366, closes #237) — 70,000+
  public-domain books via Gutendex. New `:source-gutenberg` module:
  catalog browsing via the JSON API; add-to-library downloads each
  book's EPUB to `cacheDir/gutenberg/<id>.epub` and renders chapters
  through `:source-epub`'s parser. Most-legally-clean source in the
  storyvox roster — PG actively encourages programmatic access.
  Defaults to ON for fresh installs.

### Changed
- New `BrowseSourceKey.Gutenberg` chip in the picker; supports
  Popular / NewReleases / Search tabs. BestRated has no analogue on
  PG. No filter sheet in v1 — topic search through the Search tab
  covers the discovery cases.

## [0.5.14] — 2026-05-13

### Added
- **Royal Road soft sign-in gate on Browse listings** (#365, closes
  #241) — when the user is not signed in to RR, the Browse → RR
  Popular / NewReleases / BestRated / filter-active tabs render a
  brass sign-in CTA instead of firing an anonymous request. Search
  and Add-by-URL stay open anonymously. Authenticated traffic
  removes the "anonymous bot" framing — every listing fetch now
  carries a real RR session cookie. Closes #240 as superseded
  (#241's soft alternative chosen).

### Changed
- `BrowseViewModel` — three auth signals (gh sign-in, gh repo scope,
  rr sign-in) now bundle through a single `AuthSnapshot` flow so the
  outer controls combine stays under the 5-arg overload ceiling
  after gaining a third boolean.

## [0.5.13] — 2026-05-13

### Added
- **EPUB export from FictionDetail** (#364, closes #117) — overflow-menu
  "Export as EPUB" action. New `:source-epub-writer` module mirrors
  the reader/import that landed in #235: persisted rows assemble into
  a valid EPUB 3.0 zip at `cacheDir/exports/<sanitized-title>.epub` and
  hand off to the Android share-sheet through a scoped FileProvider
  (`xml/file_paths.xml` only exposes `exports/`, not the rest of
  cacheDir). `<dc:source>` metadata names the original backend
  (Royal Road, RSS, Outline, GitHub, EPUB).

### Changed
- `ChapterDao.allChapters(fictionId)` — new single-pass query returning
  every chapter row (including bodies) for export. Independent of the
  shelves/history v6 schema; no migration.

## [0.5.12] — 2026-05-13

### Added
- **InstantDB cloud sync foundation** (#360, #158-adjacent) — new
  `core-sync` module syncing library, follows, playback positions,
  bookmarks, pronunciation dictionary, and secrets through InstantDB.
  Magic-code sign-in screen, conflict policies per syncer, 24h tombstone
  TTL so re-adds propagate, PBKDF2 600k rounds (NIST 2024 / OWASP) for
  user-derived keys, format-v2 envelope. App cold-start initializes the
  sync graph.
- **Library shelves** (#362, closes #116) — predefined Reading / Read /
  Wishlist shelves with many-to-many membership. Chip-row filter above
  the library grid (visible on the All sub-tab), long-press a cover to
  open the manage-shelves bottom sheet. Empty state copy reads per
  shelf instead of the generic "library is empty".
- **Reading history sub-tab** (#363, closes #158) — Library now has
  All / Reading / History sub-tabs. History is a chronological feed of
  every chapter open, most-recent first, with relative-time labels
  ("2h ago"). Tapping a row opens the reader at that chapter without
  auto-starting audio. Forever retention.
- **Magical resume prompt on the Playing tab** (#361) — when the user
  has paused mid-chapter, opening Playing surfaces a brass-themed
  Library Nocturne prompt to resume from the saved offset.

### Changed
- `LibraryTab.Reading` coerces the chip filter to `OneShelf(Reading)`
  internally, so the same shelf-scoped Room flow drives both surfaces.
  Chip row is hidden on the Reading and History tabs (the tab is the
  filter / history is its own feed).
- Room database schema bumped to **v6**. Migration chain is `1→2→3→4→5→6`
  with all steps purely additive — no existing data touched. `v5` adds
  `fiction_shelf` (junction), `v6` adds `chapter_history` (one row per
  fiction+chapter, upsert on open).

### Fixed
- (Post-merge fix) `ChapterDao.allBookmarks()` newly-abstract member
  broke the test fixtures' two `FakeChapterDao` stubs; both now
  implement the override.

## [0.5.11] — 2026-05-12

### Fixed
- Bottom-tab taps lost under playback recomposition (#359) — dropped
  `popUpTo + saveState/restoreState` with mixed enter/exit transitions
  that committed the back-stack swap without rendering. Trade-off is
  lost tab-scroll-position memory.

## [0.5.07] — 2026-05-12

### Fixed
- RSS chapter reorder crash (#350) — atomic two-phase chapter upsert
  parks existing indexes to `+100_000` inside a `@Transaction` before
  upserting the fresh batch, preventing SQLite's immediate UNIQUE
  constraint check from firing mid-batch.

## Earlier — see `git log`

Releases v0.5.00 through v0.5.10 predate this changelog. The git log
captures their contents — every `release: vX.Y.ZZ` commit has a
substantive body. Notable milestones:

- **v0.5.00** (2026-05-10) — milestone release. UX wave (audit, research,
  build, grind), played indicators, nav/playback survival, settings
  shimmer, browse polish.
- **v0.5.07** (2026-05-12) — RSS reorder UNIQUE-constraint crash fix.
- **v0.5.10** (2026-05-12) — chapter bookmarks (#121) + self-hosted CI
  runner (#358) migrating off the capped jphein hosted-Actions minutes.
- **v0.5.11** (2026-05-12) — library nav fix while audio is playing.
