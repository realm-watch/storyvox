# GitHub as a Second Fiction Source — Design

**Status:** Draft, awaiting JP approval before implementation.
**Author:** Claude (storyvox session, 2026-05-06).

## Goal

Let storyvox treat a GitHub repo as a fiction the same way it treats a Royal Road `/fiction/{id}` URL today: add it, see chapters, listen with sentence highlighting, sync new chapters as commits land. Royal Road remains the primary source; GitHub is the next plugin.

## Why GitHub specifically

- Some web-fiction authors already mirror or originally publish on GitHub (mdbook-style repos, plain markdown chapters).
- Free, no anti-bot wall (storyvox already has to fight Cloudflare for RR).
- Public API gives us commit history → "new chapter" detection is just a `compare` call instead of polling HTML.
- Plays well with the realm aesthetic ("the Library accepts new tomes from any forge").

## Non-goals (v0.4)

- Private repos — defer until a clean GitHub OAuth path exists.
- Voice-tagging from manifest (`narrator: "en-US-Andrew"`) — interesting later, not now.
- ePub/PDF/Gutenberg ingest — out of scope.

## Architecture

### Multi-source refactor (prerequisite)

The current data layer assumes one source: `FictionRepository` takes `private val source: FictionSource`. Adding GitHub forces us to route per-fiction by `sourceId`.

**Change:**

```kotlin
// :core-data
@Inject constructor(
    sources: Map<String, @JvmSuppressWildcards FictionSource>,
    // ...
) {
    private fun sourceFor(sourceId: String): FictionSource =
        sources[sourceId] ?: error("Unknown source: $sourceId")
}
```

Each source module contributes via Hilt `@IntoMap @StringKey`:

```kotlin
@Binds @IntoMap @StringKey("royalroad")
abstract fun bindRoyalRoad(impl: RoyalRoadSource): FictionSource

@Binds @IntoMap @StringKey("github")
abstract fun bindGitHub(impl: GitHubSource): FictionSource
```

Routing key is the `sourceId` already stored on `FictionSummary` and persisted in Room.

### `:source-github` module shape

Mirrors `:source-royalroad`:

```
source-github/
  src/main/kotlin/in/jphe/storyvox/source/github/
    GitHubSource.kt              ← FictionSource impl
    GitHubApi.kt                 ← OkHttp + kotlinx.serialization client
    manifest/
      BookManifest.kt            ← parsed book.toml + storyvox.json union
      ManifestParser.kt
    parser/
      MarkdownChapterRenderer.kt ← MD → HTML (commonmark) + plaintext
    registry/
      Registry.kt                ← reads & caches storyvox-registry JSON
    di/
      GitHubModule.kt
```

## Manifest convention

Two formats accepted, in priority order:

### 1. `book.toml` (mdbook standard) — primary

```toml
[book]
title = "The Archmage Coefficient"
authors = ["onedayokay"]
description = "An overpowered archmage..."
language = "en"
src = "src"
```

mdbook also implies: `src/SUMMARY.md` lists chapters in order, e.g.:

```markdown
# Summary
- [Master Elric and the Spirit Orbs](chapters/01-master-elric.md)
- [The Brass Gate](chapters/02-brass-gate.md)
```

This gives us title, author, chapter order, chapter file paths — enough for the listing.

### 2. `storyvox.json` — extension manifest (optional)

For metadata mdbook doesn't have:

```json
{
  "version": 1,
  "cover": "assets/cover.png",
  "tags": ["fantasy", "litrpg"],
  "status": "ongoing",
  "narrator_voice_id": "en-US-Andrew:DragonHDLatestNeural",
  "honeypot_selectors": []
}
```

Lives at the repo root; entirely optional. If absent, defaults: no cover, empty tags, status=ONGOING, no voice override.

### 3. Bare-repo fallback

If neither manifest exists but the repo has a `chapters/` or `src/` directory containing numbered markdown files matching `^\d+[-_].*\.md$`, treat it as a fiction with:

- `title` = repo name (kebab-case → Title Case)
- `author` = repo owner login
- `chapters` = sorted by leading number, title from `# Heading` of each file

Lets users point at unmarked-up repos without forcing them to add a manifest, while documenting the manifest as the canonical path.

## Chapter rendering

- Markdown → HTML via `org.commonmark:commonmark` (small, no jvm-only deps).
- HTML → plaintext via storyvox's existing `HoneypotFilter` pipeline plus a markdown-aware stripper.
- Honor `storyvox.json.honeypot_selectors` if present (allows authors to mark "audio-only" or "visual-only" passages).

## Identifiers

A GitHub fiction's stable id is `github:owner/repo` (lowercased). Examples:

```
github:jphein/example-fiction
github:onedayokay/the-archmage-coefficient
```

Chapter ids are `<fictionId>:<chapter-path-relative-to-repo>`:

```
github:jphein/example-fiction:src/01-elric.md
```

`sourceId` field stays `"github"`. Persisted in Room exactly the same as RR rows; the routing key is what's already there.

## Discovery

### Add by URL (primary entry point — accepts any source)

Library tab gets a brass `+` FAB. The paste sheet is **source-agnostic** — paste anything, storyvox routes by URL pattern:

| Pattern matched | Routed to |
|---|---|
| `https://www.royalroad.com/fiction/{id}` (and `/chapter/{id}`) | `:source-royalroad` |
| `https://github.com/{owner}/{repo}` (and `/tree/{branch}`) | `:source-github` |
| `github:{owner}/{repo}` or `{owner}/{repo}` | `:source-github` |

A small `UrlRouter` in `:core-data` runs the regex match and returns `Pair<sourceId, fictionId>`; the repo's `addByUrl(url)` looks the source up via the new multi-binding map and calls `fictionDetail(fictionId)` on it. Same code path the registry uses internally.

Resolver flow per source:

- **Royal Road**: existing detail-page fetch path; nothing new.
- **GitHub**: `GET /repos/{owner}/{repo}` → existence check → manifest fetch → parse → seed `FictionDetail` and persist.

Either way the fiction shows up in Library immediately and the user lands on its detail screen.

### Registry (curated, featured row)

A separate `jphein/storyvox-registry` repo holds `registry.json`:

```json
{
  "version": 1,
  "fictions": [
    {
      "id": "github:jphein/example-fiction",
      "tags": ["fantasy", "demo"],
      "featured": false,
      "added_at": "2026-05-06"
    }
  ]
}
```

storyvox fetches `registry.json` from `raw.githubusercontent.com` once per session (cached). Registry entries appear as the **Featured** row at the top of Browse → GitHub — pinned, curated, hand-picked. The rest of the screen is Search.

### Browse + filter (Search-driven)

Browse → GitHub is a real searchable surface, not just the registry. Two queries run in parallel:

1. **`GET /search/repositories?q=topic:fiction+{userQuery}`** — pulls anything on GitHub tagged `topic:fiction` (or `topic:fanfiction`, `topic:webnovel` — registry-configurable union of topics). 30 req/min unauthenticated, plenty for a typed-search UX with a 300ms debounce.
2. **Registry filter** — same `userQuery` filtered client-side over the cached registry list.

Results merge with registry entries pinned to the top.

#### Filter dimensions

The filter sheet (mirrors the existing Royal Road BrowseFilter) exposes:

| Filter | Source of truth | Behaviour when missing |
|---|---|---|
| **Tags** (multi-select: fantasy, litrpg, fanfic, sci-fi, …) | `storyvox.json.tags`, else GitHub `topics` array | Empty tag list = unfiltered |
| **Status** (ongoing / completed / hiatus / dropped) | `storyvox.json.status`, else inferred from days-since-last-commit (>180d=hiatus, >365d=dropped, default=ongoing) | Default to ongoing |
| **Length** (min / max chapter count) | `SUMMARY.md` parsed length, cached | Treated as "unknown", excluded from min-chapter filters but included from max |
| **Last updated** (cutoff) | repo's `pushed_at` from API | Always available |
| **Min stars** | repo `stargazers_count` | Always available |
| **Language** | repo `language` (ISO code in `book.toml.language` overrides) | Defaults to `en` |
| **Sort** | popularity (stars), recency (`pushed_at`), title | Always available |

**Filter quality scales with manifest adoption.** Repos with a `book.toml` + `storyvox.json` filter precisely; bare repos still filter on what GitHub itself exposes (stars, recency, language, topics). The "Add manifest to your repo" doc on the storyvox README is what we point authors at.

#### Caching

- **Search results** — keyed by `(query, filters)` → cached 30 minutes in Room. Stale-while-revalidate so scrolling the same filter is instant.
- **Repo metadata** — keyed by `github:owner/repo` → cached until the repo's `pushed_at` changes. The cache row also stores parsed manifest blob.
- **Registry JSON** — fetched once per session, cached in memory.

The 60-req/hr unauthenticated cap is fine: typical browse session is one search + one or two filter changes ≈ 4 search calls + 0–10 metadata fetches for visible cards. Aggressive caching keeps a power user under the cap.

## Auth + rate limits

- v1 ships **unauthenticated** — 60 GitHub API requests/hour per IP.
- Manifest + chapter reads use `raw.githubusercontent.com` which is **not rate-limited** the same way.
- Add an optional **GitHub PAT** field in Settings → "Sources" for power users. PAT lifts the limit to 5,000/hr. Stored in `EncryptedSharedPreferences` next to RR cookies.
- 60/hr is enough for everyday use as long as we cache aggressively and only hit `/repos/...` on the initial add and on explicit refresh.

## Sync model

- "New chapter" detection: store the latest commit SHA we've seen for each fiction. On refresh, `GET /repos/{owner}/{repo}/commits?since={timestamp}` → if any commit changed a chapter file, mark fiction "has updates" and re-fetch SUMMARY.md.
- Existing `ChapterDownloadWorker` reused — just hits a different URL.
- `WorkManager` poll interval respects user's existing setting from Settings → Downloads → "Poll every Nh".

## Honeypot / TTS hygiene

GitHub markdown is much cleaner than RR's HTML — no `display:none; speak:never` anti-piracy spans. Default honeypot config is empty. Authors who want audio-only or visual-only blocks declare them in `storyvox.json.honeypot_selectors`.

## UI surface changes

| Surface | Change |
|--------|--------|
| Library | Add brass `+` FAB → "Add fiction by URL" sheet |
| Browse | Top-level tabs become `Royal Road / GitHub` once GitHub source is enabled |
| Settings | New "Sources" section with GitHub PAT input + registry-refresh button |
| Fiction detail | New chip showing source: `📚 Royal Road` or `🐙 GitHub` |
| Reader | No change |

## Build sequence

Implementation order so each step ships value on its own:

1. **Multi-source refactor** — Hilt multibindings, `sourceFor(id)` in repos. Keep one source bound. No user-visible change. *Tag: storyvox v0.3.x*
2. **`UrlRouter` + paste-anything sheet** — works against current single source (RR). Library `+` FAB, regex-routed. *Visible win even before GitHub source exists.*
3. **`:source-github` module + `GitHubApi`** — OkHttp client, models for `repo`, `commit`, `tree`, `search`. Just talks to API. No UI.
4. **Manifest parsing** — `BookManifest` reading `book.toml` + `storyvox.json` + bare-repo fallback. Unit-tested with example repos.
5. **Chapter rendering** — markdown → HTML/plaintext path. Reader works with a manually-seeded GitHub fiction.
6. **Add-by-URL routes to GitHub** — paste sheet now resolves GitHub URLs end-to-end through `:source-github`.
7. **Browse → GitHub: registry-only** — Featured row from `registry.json`. Lands the curated surface without depending on Search yet.
8. **Browse → GitHub: search + filters** — wire `/search/repositories`, build the filter sheet, merge results with registry.
9. **Sync** — commit-SHA-based polling. ChapterDownloadWorker hits raw.githubusercontent.com.

Each step ships value on its own. Steps 1–2 are pure refactor + UX win; step 7 lands the curated browse surface; step 8 is the full filter UX.

Each step is its own commit + tag candidate. Step 1 alone is meaningful (cleans up an architectural assumption); step 5 is the first user-visible win.

## Tradeoffs called out

- **Browse divergence** — RR's "popular" / "latest" / "best by genre" don't translate to GitHub. The Browse tab grows a source picker; UI patterns diverge by source. Acceptable, but means "Browse" is no longer a single concept.
- **Manifest discovery vs heuristic** — preferring `book.toml` is friendly to the existing mdbook ecosystem but adds a parse path. Bare-repo fallback prevents that from being a hard requirement at the cost of guessing structure.
- **No GitHub Search** — discovery is limited to "users paste URLs" + curated registry. Could extend later but Search API quality is poor for fiction.
- **PAT in encrypted prefs** — same security model storyvox already uses for RR cookies; not new attack surface but worth being deliberate about.
- **GPL-3.0 implications** — storyvox is GPL-3.0 (matches VoxSherpa); the registry repo can be a mix of MIT/CC0 metadata referencing fictions under whatever license. We don't redistribute fiction text — we link to it — so compatibility is the fiction author's problem.

## Decisions (so far)

- **2026-05-06 — Add-by-URL accepts any source.** Paste sheet is source-agnostic; URL pattern decides routing.
- **2026-05-06 — Registry: JP-owned, no PR governance.** `jphein/storyvox-registry` with informal direct commits; revisit if external contributors show up.
- **2026-05-06 — GitHub PAT: not in v1.** Ship unauthenticated (60 req/hr). Add optional Settings → Sources → "GitHub token" once anyone hits the limit.
- **2026-05-06 — GitHub Search API is in scope for v1.** Browse → GitHub combines a registry "Featured" row with `/search/repositories?q=topic:fiction+...` results, filtered by tags/status/length/recency/stars/language. Filter quality scales with manifest adoption; bare repos still filter on what GitHub itself exposes.

## Open questions

None blocking implementation. Spec is ready to execute.
