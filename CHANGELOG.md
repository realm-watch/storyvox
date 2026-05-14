# Changelog

All notable changes to storyvox land here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
the `versionName` in `app/build.gradle.kts` and the `v*` git tag.

Entries before v0.5.12 are reconstructed from the git log — see
`git log --oneline` for the exhaustive record.

## [Unreleased]

## [0.5.29] — 2026-05-14

### Added
- **Wear OS Library Nocturne theme + circular scrubber** (#406, closes #192) — `:wear` module gets a brass-on-warm-dark theme matching the phone/tablet. `NowPlayingScreen` now wraps a Coil-loaded cover with a brass `CircularScrubber` on round watches (square form factor falls back to a brass-tinted linear scrubber). 3-button transport row wired to the existing `PhoneWearBridge`. Five `@Preview` entries cover round/playing, round/paused, round/buffering, small-round, and square so the visual diff is reviewable from the preview pane.
- **Voice library search + language filter** (#413, closes #264) — sticky search bar + horizontally-scrolling language filter chips at the top of the voice picker. Type-to-filter on voice display name (200ms debounce); chips derived dynamically from installed-voice languages (English-first, then alphabetical). Filters apply via `combine(installedVoices, query, language)` in the ViewModel; the existing favorites star + Starred surface still pin voices on top. Closes the "1188 voices, no search, unusable on Flip3" pain. 15 new `VoiceFilterTest` cases.
- **Room+Robolectric DAO test layer** (#410, closes #48) — 43 new DAO tests covering `FictionDao`, `ChapterDao`, `PlaybackDao` with slim-projection regression coverage, `@Transaction` boundaries, and `CASCADE` behavior. Tests run on a real in-memory Room database under Robolectric so SQL fidelity is preserved across the dependency graph. Pure additive — no production code touched.

### Changed
- **Browse source picker is now a scrollable `LazyRow` of `FilterChip`s** (#411, closes QA-found #407) — replaces the previous segmented-button row which mid-word-wrapped on tablet and silently hid the rightmost chips (arXiv / PLOS / Notion couldn't be reached on narrow viewports). Active chip carries the brass active-state coloring; the row pans horizontally so every backend stays reachable regardless of viewport width.

### Refactored
- **`AuthRepositoryImpl` now uses `@ApplicationScope CoroutineScope`** (#405, closes #30) — replaces the bare-`CoroutineScope` `init { ... }` block flagged as deferred-to-v1.0-hardening. Injected scope uses `SupervisorJob` for structured concurrency and is trivially swappable with `TestScope` in unit tests. No behavior change at runtime; auth init is structurally identical from the user's perspective.

### Build state
- All five PRs (#405 / #406 / #410 / #411 / #413) shipped as a bundle merge with no inter-PR conflict — each PR was independently CI-green before the wave landed, and the wave merged in dependency order (refactor → tests → small fix → voice picker → Wear) so cross-touching files (`BrowseScreen.kt`, voice picker) re-rebased cleanly between merges.

## [0.5.28] — 2026-05-13

### Added — four new backends, all using the v0.5.27 `@SourcePlugin` pattern from day one
- **Wikisource** (#376, #399) — Wikimedia project for transcribed public-domain texts (Shakespeare, classic novels, historical documents). Browse landing reads `Category:Validated_texts` (the double-proofread quality tier); free-form search via MediaWiki Action API. Multi-part works walked via `/Subpage` traversal; single-page works fall back to Wikipedia-style heading_1 splits. 14 unit tests. Default OFF on fresh installs.
- **arXiv** (#378, #400) — open-access academic pre-print server (physics, math, CS). Default category for browse is `cs.AI`; free-form search via the public `export.arxiv.org/api/query` Atom feed. Each paper is one fiction; v1 chapter is the abstract + title + author byline + comments rendered from the `arxiv.org/abs/<id>` HTML page. Full-PDF body extraction is an explicit follow-up scope cut. 12 unit tests. Default OFF.
- **Hacker News** (#379, #401) — front-page tech-news threads as single-chapter fictions. Popular surfaces the first 50 of HN's top-stories Firebase list; Search hits the Algolia HN Search API. Link stories include the title + URL + top 20 comments (threaded with `—` depth prefixes); Ask HN / Show HN use the story `text` field directly. 8 unit tests. Default OFF.
- **PLOS / Public Library of Science** (#380, #402) — open-access peer-reviewed science (PLOS ONE, Biology, Medicine, Comp Biology, Genetics, Pathogens, Neglected Tropical Diseases). Browse landing reads recent PLOS ONE sorted by publication date; Search hits the same Solr endpoint with free-form `q=`. Each article (one DOI) is one fiction; v1 chapter is the abstract + first ~3 body sections. 11 unit tests. Default OFF.

### Total backend count
- **16 fiction backends** now: Royal Road, GitHub (curated registry), Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource (new), KVMR (audio-stream), Notion, Hacker News, arXiv (new), PLOS (new). All four new backends register via `@SourcePlugin` and surface in the `SourcePluginRegistry` automatically — adding new backends is now ~4 touchpoints instead of 17 (per the Phase 2 #384 work that shipped in v0.5.27).

### Under the hood
- Bundle merge orchestrated per `feedback_no_eager_merge_in_bundle`: all four PRs (#399, #400, #401, #402) opened in parallel from worktrees, merged in order (Wikisource → HN → arXiv hand-rebased → PLOS hand-rebased) once each backend's CI was independently green. arXiv and PLOS branches required hand-rebase + union-resolve of additive enum + Settings UI + test-fake additions; the agent-curated commits and tests survived intact.
- Five test fakes (`ChatViewModelTest`, three `SettingsViewModel*Test` flavors, and `RealPlaybackControllerUiTest`) updated with `setSourceArxivEnabled` / `setSourcePlosEnabled` stubs to match the new `SettingsRepositoryUi` interface members.

## [0.5.27] — 2026-05-13

### Added
- **Plugin seam — Phase 2: 11 remaining backends migrated to `@SourcePlugin`** (#384) — every fiction source (`royalroad`, `github`, `mempalace`, `rss`, `epub`, `outline`, `gutenberg`, `ao3`, `standard_ebooks`, `wikipedia`, `notion`) now carries a `@SourcePlugin(id=…, displayName=…, defaultEnabled=…, category=Text, supportsFollow=…, supportsSearch=…)` annotation on its `FictionSource` impl, with `ksp(project(":core-plugin-ksp"))` wired in each module's `build.gradle.kts`. The KSP processor emits one `@Provides @IntoSet SourcePluginDescriptor` Hilt module per annotated class, so the `SourcePluginRegistry` singleton now exposes the full 12-plugin roster (the 11 fiction backends + KVMR from Phase 1). Existing `@IntoMap @StringKey` Hilt bindings are intentionally kept — Phase 2 is additive over the legacy wiring; Phase 3 will delete the legacy `BrowseSourceKey` enum and the per-source `sourceXxxEnabled` booleans on `UiSettings` once the registry-driven UI lands.
- **`SourcePluginRegistry` duplicate-id guard** (#384) — registry `init` block now hard-fails at app startup with an `IllegalStateException` listing the offending ids when two `@SourcePlugin` annotations declare the same id. Catches a copy-paste mistake on a fresh-source addition before Hilt's silent which-one-wins multibinding behaviour can ship.
- Two new `SourcePluginRegistryTest` cases: the duplicate-id guard, and a Phase 2 roster contract test that asserts the registry surfaces all 12 expected `SourceIds.*` ids via `byId` and matches the expected size.



### Added
- **Cross-source Inbox tab in Library** (#383) — new fourth Library sub-tab (`All / Reading / Inbox / History`) surfaces a chronological feed of source-emitted events: "3 new chapters in The Wandering Inn", "KVMR live now", and (future) Wikipedia article updates. Tap a row to deep-link to the chapter/program; an unread-count badge sits on the tab itself. The feed is source-agnostic — one timeline across every backend that emits update events.
- **Per-source Inbox notification toggles** (#383) — Settings → Library & Sync gets an "Inbox notifications" sub-card with one switch per emitting backend (`Royal Road`, `KVMR`, `Wikipedia`). Default ON; flipping a toggle OFF stops the backend's update emitter from writing rows to the cross-source feed without affecting library updates or the source's visibility in Browse.
- **`InboxRepository` + `inbox_event` table** (#383) — Room migration v7 → v8 lands the append-only event table backing the Inbox. Repository coalesces consecutive "N new chapters" events for the same fiction so the feed doesn't flood after a long offline gap. No FK to fiction/chapter — events deliberately survive parent-row removal so the user can still see "Wikipedia: X updated" after they unfollow the article.
- `NewChapterPollWorker` (#383) now records an `InboxEvent` row alongside its existing chapter-diff persistence whenever a polled source has missing chapters and the user hasn't muted that source in the Inbox toggles. KVMR live-program emission + Wikipedia article-diff emission are tracked as follow-ups — v1 wires the seam but only Royal Road's existing poll path emits today.
- **Vertex AI service-account JSON auth** (#219, #397) — Settings → AI → Vertex gains a SAF JSON file picker alongside the existing API-key field. Picked service-account JSON is parsed/validated, encrypted at rest in `EncryptedSharedPreferences`, and used to mint 1-hour OAuth access tokens on demand (JWT-bearer RFC 7523, signed in-process with `java.security` — no `google-auth-library` dep). Tokens cached until ~5 min before `expires_in` and refreshed transparently. Mutually exclusive with the API-key mode at the storage layer. 20 new tests cover parse validation, JWT sign+verify, token cache lifecycle, and end-to-end Vertex SA dispatch via MockWebServer.
- **Plugin seam — Phase 1 scaffolding** (#384, #396) — `@SourcePlugin` annotation in `:core-data`, a KSP SymbolProcessor in the new `:core-plugin-ksp` module that emits Hilt `@IntoSet` factories for each annotated `FictionSource`, and a `SourcePluginRegistry` singleton that consumes the multibinding. New `pref_source_plugins_enabled_v1` JSON map preference (id → enabled) seeded from the existing per-source `SOURCE_*_ENABLED` boolean keys via the one-shot `SourcePluginsMapMigration`, with dual-write from every legacy setter so the old `UiSettings.sourceXxxEnabled` observers stay in sync. `:source-kvmr` migrated as the worked example (one `@SourcePlugin(id="kvmr", …)` line + a `ksp(project(":core-plugin-ksp"))` dep — its existing `@IntoMap @StringKey` Hilt binding is intentionally kept for Phase 1). Phase 2 (follow-up PRs) migrates the remaining 11 backends and switches BrowseScreen + Settings to iterate the registry.

## [0.5.25] — 2026-05-13

### Added
- **Anonymous Notion-site reader mode** (#393, closes the v0.5.24 known limitation) — `:source-notion` now reads public-shared Notion pages via the *unofficial* `www.notion.so/api/v3` surface (`loadPageChunk`, `queryCollection`, `syncRecordValuesMain`, `getPublicPageData` — the same set [react-notion-x](https://github.com/NotionX/react-notion-x)'s `notion-client` package uses). Zero setup: a fresh install opens Browse → Notion and immediately surfaces TechEmpower's content as narratable audio, with no integration token required.
- **Four-fiction TechEmpower layout (revised mid-cycle)** — Browse → Notion shows **four tiles**, one per top-level section of the techempower.org navigation. Each section is its own fiction, and each article inside it is its own chapter:
  - **Guides** — 8 chapters, one per curated guide page (How to use TechEmpower, Free internet, EV incentives, EBT balance, EBT spending, Findhelp, Password manager, Free cell service). Chapter order matches `techempower/site.config.ts` `pageUrlOverrides`.
  - **Resources** — N chapters (~80), one per row in the TechEmpower Resources database (queried via `queryCollection`). Each chapter renders the row's underlying Notion page content.
  - **About** — single-chapter fiction with the About page content.
  - **Donate** — single-chapter fiction with the Donate page content.
  This is a course correction from the v0.5.25-rc design that landed in PR #394 as a single-fiction-multi-chapter layout — JP redirected to "four books, each article a chapter" mid-cycle. The delegate, NotionDefaults, and AnonymousNotionDelegateTest were rewritten before tagging v0.5.25 so the released APK has the new shape.
- **`NotionConfig` mode enum** — new `NotionMode { ANONYMOUS_PUBLIC, OFFICIAL_PAT }` selects the read path. Anonymous mode reads any public-shared root page id; PAT mode keeps the original integration-token + database-id flow for private workspaces. The mode is implicit: blank token → anonymous, non-blank token → PAT. Existing users with a stored PAT keep their current experience unchanged.

### Fixed
- **Stale "TODO placeholder" rejection in `NotionApi.requireConfigured`** — v0.5.23 shipped a check that fast-failed when `databaseId == TECHEMPOWER_DATABASE_ID`; v0.5.24 replaced that id with the real TechEmpower Resources DB but left the check, silently breaking the bundled default for anyone with a PAT shared to the Resources DB. v0.5.25 removes the equality check; gating is now token presence alone in PAT mode and root-page-id presence in anonymous mode.

### Implementation
- `NotionUnofficialApi` (new) — OkHttp client for the four `/api/v3` endpoints with hand-crafted JSON bodies (the queryCollection loader shape is deeply nested; full kotlinx-serialization round-trips would be more code than the bodies). Process-lifetime in-memory cache keyed on hyphenated page id; deduplicates round-trips within a Browse → detail flow. Every HTTP call is wrapped in `withContext(Dispatchers.IO)` so the source can be safely called from any coroutine context.
- `AnonymousNotionDelegate` (new) — implements the FictionSource surface against `NotionUnofficialApi`. Builds a single Browse tile from the configured root page and resolves its chapter list from `NotionDefaults.techempowerChapters` (a hand-curated list of `ChapterSpec.Page` / `ChapterSpec.Collection` entries). Page chapters render their underlying Notion page's blocks via `renderPageBody`; collection chapters query the database via `queryCollection` and render a row-title list. Tombstoned blocks (`alive:false`) are filtered.
- `NotionConfigImpl` (modified) — persists a new `pref_notion_root_page_id` DataStore key. Defaults to `NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID` on first install; users can override via Settings.
- `NotionApi.requireConfigured` (bug fix) — removed the stale `databaseId == TECHEMPOWER_DATABASE_ID` placeholder check that v0.5.24 silently broke when it baked the real DB id into the same constant.
- 23 new unit tests in `AnonymousNotionDelegateTest` + `NotionUnofficialModelsTest` covering the recordMap envelope decode, decoration-array title extraction, page-id hyphenation, collection_view metadata read, chapter spec resolution (TechEmpower vs. generic), page-body rendering with tombstone filtering, collection-row title extraction + sorting, HTML/plain projection of the unofficial block types, mode-posture defaults, and tolerance for unknown top-level recordMap fields.

### Known caveats
- The unofficial `www.notion.so/api/v3` endpoints are undocumented; Notion may change their shape without notice. Storyvox decodes permissively (all block-payload fields are `JsonElement`) so unknown variants degrade silently rather than breaking parsing. Surface errors come back as structured `NotionUnofficialError` envelopes (`{isNotionError, errorId, name, message}`) which we map to standard `FictionResult.AuthRequired`/`NotFound`/`RateLimited`/`NetworkError`.

## [0.5.24] — 2026-05-13

### Fixed
- **`NotionDefaults.TECHEMPOWER_DATABASE_ID` now points at the real TechEmpower Resources database** (`2a3d706803c649409e74e9ce5ccd4c4b`, from `techempower/site.config.ts` line 48). Replaces the `TODO_FILL_IN_...` placeholder that shipped in v0.5.23. Users with their own Notion integration token shared with the database now see TechEmpower's Resources content as the default Notion fiction.

### Known limitation
- v0.5.24 still requires the user to paste a Notion integration token. The TechEmpower content lives at a publicly-shared `techempower.notion.site` URL, which is readable anonymously via Notion's *unofficial* `www.notion.so/api/v3/{loadPageChunk,queryCollection}` endpoints — but the official Notion REST API (which `:source-notion` currently uses) always returns 401 without auth, even for public content. v0.5.25 will land the anonymous-read mode + extend the tree to cover Guides + About + Donate alongside the Resources database (#393).

## [0.5.23] — 2026-05-13

### Added
- **Notion as a 12th fiction backend** (#391, closes #233 #390) — `:source-notion` module brings Notion databases into Browse alongside the other eleven backends. Database query → fiction list; pages split into chapters on every `heading_1`. PAT-based auth (Notion integration token), stored encrypted alongside the Outline / Palace / Royal Road / Wikipedia tokens. The token + database id are pasted in Settings → Library & Sync → Notion. 21 unit tests cover the API mappers, paginator, and config plumbing.
- **Notion default-on** (#390) — `sourceNotionEnabled = true` for fresh installs; existing users keep their stored preference. The default `databaseId` points at a `TODO_FILL_IN_TECHEMPOWER_DATABASE_ID` placeholder that returns a clean "Notion database id not configured" empty-state until JP pastes the real id (see [[NotionDefaults.kt]]).

### Changed
- Browse → Notion shows up as the rightmost source chip; Settings → Library & Sync has an inline NotionConfigRow with database-id + token fields.
- README + docs/index.md updated to "Twelve fiction sources" — the recurring "Six" / "Eleven" framing finally catches up to the actual surface.

### Known limitation
- The default `TECHEMPOWER_DATABASE_ID` is a placeholder string. The techempower.org website is backed by a Notion **page** (root page id `0959e445...`), not a database. Storyvox's `:source-notion` queries the Notion API's `databases/query` endpoint, which is a different object kind. A future change either (a) creates a separate Notion *database* in the techempower workspace to point at, or (b) extends `:source-notion` with a page-rooted hierarchy mode similar to the way `react-notion-x` traverses the website's root page. Until then, the default install shows the empty-state "Notion database id not configured (TODO placeholder still in use)" until the user pastes their own database id.

## [0.5.22] — 2026-05-13

### Infrastructure
- **Four sibling repos moved jphein → techempower-org** — storyvox-registry, speech-to-cli, cloud-chat-assistant, gnome-speaks. The `:source-github` Featured-row fetcher (`Registry.kt`) had a hardcoded URL pointing at `raw.githubusercontent.com/jphein/storyvox-registry/main/registry.json` — flipped to `techempower-org`. The old URL still 200s via GitHub's permanent raw redirect (so existing v0.5.21 installs keep working), but the new canonical URL is now baked into the v0.5.22 binary.
- USER_AGENT updated `storyvox/0.4 (+https://github.com/jphein/storyvox)` → `storyvox/0.5 (+https://github.com/techempower-org/storyvox)` so server-side logs / Plausible-style analytics see the canonical UA from this release forward.
- README + docs/index.md + docs/ROADMAP.md + settings.gradle.kts comment swept for `jphein/` references; spec docs left frozen as historical record.

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
