# Changelog

All notable changes to storyvox land here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
the `versionName` in `app/build.gradle.kts` and the `v*` git tag.

Entries before v0.5.12 are reconstructed from the git log — see
`git log --oneline` for the exhaustive record.

## [Unreleased]

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
