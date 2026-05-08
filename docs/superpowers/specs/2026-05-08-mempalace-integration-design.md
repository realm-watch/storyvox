# MemPalace as Fiction Source — Design

**Status:** Draft, awaiting JP approval before implementation.
**Author:** Yara (storyvox session, 2026-05-08).
**Closes:** #79.

---

## Goal

Let storyvox treat JP's [MemPalace](https://github.com/jphein/mempalace) as a fiction source the same way it treats a Royal Road `/fiction/{id}` URL or a GitHub repo. Browse the palace by wing, open a room as a "fiction", listen to its drawers as chapters with sentence highlighting. Read-only in v1. The palace is on the home LAN behind [palace-daemon](https://github.com/jphein/palace-daemon); when storyvox is off-network, the source is greyed out gracefully.

## Why this shape, and why now

The original framing (#79) was "save listening sessions to the palace" — diary writes, highlight saves, daily summaries. JP reframed mid-task: the palace **already** holds 150K+ verbatim drawers — conversation transcripts, mined project files, decisions, technical notes, planning docs. Today storyvox can't reach any of it. Treating the palace as a fiction source flips the polarity: it makes JP's own substrate listenable. The palace is amnesia-proof; storyvox is the player. They compose.

This frames the Iron Law: **palace is read in v1, write in P1+.** Save-to-palace, daily summaries, semantic search from reader, and KG integration are deferred — they are interesting but they are not what unblocks "I want to listen to my palace on the train."

## Non-goals (v1)

- **Writes** of any kind. No save-from-reader, no diary-on-listen, no KG mutation. Writes land as P1.
- **Internet exposure** of the palace. LAN-only is a hard rule (`palace-daemon` is configured for it; we don't override).
- **Embedding the palace** inside storyvox. The daemon is the boundary; storyvox is a thin HTTP client.
- **Authoring inside storyvox.** The palace's content is what it is — drawers are mined verbatim.
- **Search across the whole palace from Browse → Search.** P1; v1 search is in-room or in-wing only.
- **Cross-source filters** (e.g. "show me palace fictions tagged litrpg"). Tag dimension doesn't exist in palace primitives; not synthesising one.

## Architecture

### Reference patterns

`:source-mempalace` is a **leaf source module**, exactly the shape of `:source-github` (curated registry-driven) and `:source-royalroad` (HTML-scraped). Each defines a single `FictionSource` impl, its own DI module contributing `@IntoMap @StringKey`, its own qualified `OkHttpClient`, its own model classes, and a Hilt binding from `app/build.gradle.kts`.

```
source-mempalace/
  src/main/kotlin/in/jphe/storyvox/source/mempalace/
    MemPalaceSource.kt          ← FictionSource impl
    MemPalaceIds.kt              ← fiction/chapter id codec
    di/
      MemPalaceModule.kt         ← Hilt @IntoMap binding + qualified OkHttpClient
    net/
      PalaceDaemonApi.kt         ← HTTP client over palace-daemon endpoints
      PalaceDaemonResult.kt      ← daemon-side result envelope
    model/
      DrawerSummary.kt           ← `/list` response row
      DrawerDetail.kt            ← `/get_drawer` (or `/list?wing=…&offset=N&limit=1`) response
      WingsRooms.kt              ← `/graph` response shape (subset)
    config/
      PalaceConfig.kt            ← cached `(host, apiKey)` flow + reachability
```

The boundary contract — `core-data/.../FictionSource.kt` — is unchanged. The new module is purely additive; no other module needs edits beyond:

- `settings.gradle.kts` — `include(":source-mempalace")`
- `app/build.gradle.kts` — `implementation(project(":source-mempalace"))`
- `:core-data/.../SourceIds.kt` — add `const val MEMPALACE = "mempalace"`
- `:feature/.../browse/BrowseViewModel.kt` — add `MemPalace` to `BrowseSourceKey`
- `:feature/.../settings/SettingsScreen.kt` — add a "Memory Palace" section with the host field
- `:app/.../data/SettingsRepositoryUiImpl.kt` — add a `PALACE_HOST` DataStore key

That's the entire integration surface. Everything else lives behind the `FictionSource` interface and the source module is a leaf in the DI graph.

### How storyvox already routes by `sourceId`

`FictionRepository` injects a `Map<String, FictionSource>` (Hilt multibinding) and looks up by `sourceId`. Adding `:source-mempalace` is one new entry into that map:

```kotlin
@Binds @IntoMap @StringKey(SourceIds.MEMPALACE)
abstract fun bindFictionSource(impl: MemPalaceSource): FictionSource
```

The `FictionRepository` and `ChapterRepository` already do everything else — caching to Room, WorkManager-backed chapter fetches, the reader, sentence highlighting. The palace source plugs in and inherits the player.

## The mapping decision

This is the load-bearing call in the spec. Palace primitives (from `mempalace_list_wings`, `mempalace_list_drawers`, `mempalace_get_drawer`):

- **Wing** — top-level scope, derived from cwd (e.g. `projects`, `claude_code_python`, `bestiary`). 30-50 wings on JP's palace.
- **Room** — subdivision within a wing. 60+ rooms in `projects` alone (e.g. `realmwatch`, `architecture`, `technical`, `planning`).
- **Drawer** — verbatim text unit. ~150K total. Each carries a `wing/room` pair, an optional `source_file`, a `filed_at` timestamp, a `chunk_index`, and verbatim content (often a few hundred to a few thousand tokens).

Three plausible mappings, considered:

| Mapping | Fictions | Avg chapters | Verdict |
|---|---|---|---|
| **Wing = Fiction, Room = Chapter** | ~40 | ~20-60 | Rejected. A "chapter" containing thousands of drawer-tokens is unreadable. |
| **Drawer = Fiction** | ~150K | 1 | Rejected. Browse becomes a flat firehose; chapter granularity is meaningless. |
| **Room = Fiction, Drawer = Chapter** | ~600+ | varies (1 to ~5K) | **Picked.** Browseable by wing, listenable as a coherent thread. |

**Decision: `wing/room` is a fiction; each `drawer` in that room is a chapter.**

Rationale:

- A room is the natural coherence unit. `projects/realmwatch` is "everything related to realmwatch" — different drawers cover different points in time, different parts of the codebase, different decisions, but they share enough thematic spine that listening to them in `filed_at` order tells a story.
- Drawer-as-chapter gives the existing reader a sensible unit to highlight, paginate, and resume from. Chapter ids stay stable (`drawer_id` is the palace's primary key — opaque, unique, persistent).
- Room-as-fiction keeps the fiction count manageable. JP can browse "Wing: projects → 62 rooms" then pick `realmwatch` and the room becomes a 5,614-chapter audiobook of his own engineering history.
- For very-large rooms (`bestiary/technical` = 54,160 drawers), the `FictionDetail.chapters` listing is paginated; the reader streams chapters on demand. This is no different from a 5,000-chapter Royal Road webnovel — the pipe handles it.

### Identifier scheme

```
fictionId = "mempalace:<wing>/<room>"
chapterId = "mempalace:<wing>/<room>:<drawer_id>"
```

Examples:

```
mempalace:projects/realmwatch
mempalace:projects/realmwatch:drawer_projects_realmwatch_a1b2c3d4...
mempalace:bestiary/technical
mempalace:bestiary/technical:drawer_bestiary_technical_e5f6a7b8...
```

`sourceId` field on every persisted row is `"mempalace"`. `wing` and `room` are extracted by splitting on the first `/` after the prefix; `drawer_id` is everything after the chapter `:`. This codec lives in `MemPalaceIds.kt` and is unit-tested.

Drawer ids contain underscores and hex characters (`drawer_projects_configuration_6383f16abfd2a6e1af8acf5a`); they don't contain slashes or colons, which keeps the parse unambiguous.

### Wing/room legality in ids

Palace wing names are lowercase, underscore-separated tokens: `projects`, `claude_code_python`, `os.realm.watch`, `kiyo_xhci_fix`. Some contain `.` (e.g. `os.realm.watch`, `mirror.realm.watch`). Room names follow the same convention. We URL-encode the wing and room when building daemon requests; the slash separator inside `fictionId` is safe because palace wings don't contain `/` (they can't — `/` is reserved by the palace's `wing/room` keying convention).

## API shape — palace-daemon HTTP

We talked to the daemon, not directly to MemPalace. The daemon is the single-writer; reads go through it too because (a) it's the canonical surface, (b) it has the read-concurrency semaphores and warming behaviour, (c) it surfaces auth via `X-API-Key`. Local clones inspected: `/tmp/mempalace-research/` and `/tmp/palace-daemon-research/`.

Endpoints we use, drawn from `palace-daemon/main.py`:

| Endpoint | Purpose | Storyvox use |
|---|---|---|
| `GET /health` | Liveness + version. 503 when collection unavailable. | Reachability probe; back-off on 503. |
| `GET /graph` | Single-shot snapshot: `{wings: {name: drawer_count}, rooms: [{wing, rooms: {name: count}}, ...], tunnels, kg_*}`. ~0.4s on a 151K palace. | Drives Browse → Palace top level (the **wing list** with drawer counts). |
| `GET /list?wing=W&room=R&limit=N&offset=N` | Unranked listing of drawers by metadata. Returns `{drawers: [{drawer_id, wing, room, content_preview}], count, offset, limit}`. | `fictionDetail` chapter list; paginated to populate `FictionDetail.chapters`. |
| `POST /mcp` (calling `mempalace_get_drawer`) | Full content of a single drawer. Returns `{drawer_id, content, wing, room, metadata: {filed_at, source_file, …}}`. | `chapter()` returns the verbatim content as `ChapterContent`. |
| `GET /search?q=…&limit=N` | Semantic search over `mempalace_drawers`. | P1 — wired but not surfaced in Browse → Palace v1 (Search tab hidden). |

We deliberately do **not** use `/viz` (HTML-only — the dashboard the JP-side coordination message hinted at). The dashboard is what powers `https://palace.jphe.in/viz?key=...` for human visualisation; it's a self-contained HTML page that fetches `/graph`, `/repair/status`, and `/health` client-side and renders D3 + Mermaid panels. Storyvox is not a browser, so we go directly to the same JSON sources the dashboard consumes — `/graph` for structure, `/list` for content listings, `/mcp` → `mempalace_get_drawer` for chapter bodies. (Source: `palace-daemon/main.py:1161-1199` and `docs/graph-endpoint.md`.)

### Why `/graph` is the right primitive for Browse

The hint pointed us at `/viz`; `/viz` returns HTML. The data backing it is `/graph`, which is exactly the structural snapshot Browse needs:

```json
{
  "wings": {"projects": 38450, "bestiary": 65893, "claude_code_python": 2366, ...},
  "rooms": [
    {"wing": "projects", "rooms": {"realmwatch": 5614, "architecture": 10787, ...}},
    ...
  ],
  "tunnels": [...],   // ignored in v1
  "kg_entities": [...], // ignored in v1
  "kg_triples": [...],  // ignored in v1
  "kg_stats": {...}
}
```

Mapping:

- The **wings** dict drives the top-level Browse → Palace list. Each wing renders as a row card showing wing name + total drawer count + room count.
- Tapping a wing expands to its **rooms** entry from the `rooms` array. Each room is one fiction.
- Tapping a room calls `fictionDetail("mempalace:wing/room")` which calls `/list?wing=…&room=…&limit=20&offset=0` and pages forward as the user scrolls.

The `tunnels` / `kg_*` fields are ignored in v1 — interesting for the P1 Knowledge Graph exposure work, not load-bearing for "browse as audiobook."

### Daemon authentication

`palace-daemon` accepts `X-API-Key` on every endpoint. When `PALACE_API_KEY` is unset on the daemon side, auth is a no-op — fine for personal local dev. JP's production daemon at `palace.jphe.in` does require the key.

Storage: the API key is a **moderately sensitive token** (it grants read+write+delete on the palace). It goes in `EncryptedSharedPreferences` under the existing `storyvox.secrets` store, alongside Royal Road cookies. The hostname (e.g. `10.0.6.50:8080` or `palace.local:8085`) is **not** secret and lives in the regular DataStore alongside the rest of Settings. This split mirrors how RR is handled (cookie encrypted, source-id plaintext).

### Reachability + LAN model

The daemon is reached via direct HTTP to a user-supplied host:port. Three states matter:

1. **Configured + reachable.** `GET /health` returns 200 within a 1.5s timeout. Source enabled in Browse.
2. **Configured but unreachable** (off-LAN, daemon down, wrong host). `/health` times out or returns ≥500. Source greyed in Browse → Palace tab subtitle reads "Reconnect on home network." No retry-loop, no spinner-forever — single probe at tab-open and on pull-to-refresh.
3. **Not configured.** Settings → Memory Palace shows the empty host field; Browse → Palace tab is disabled and reads "Set palace host in Settings."

We do NOT auto-discover the daemon (no mDNS, no SSDP). JP types the host once. Auto-discovery is the kind of thing that sounds clever and ships subtle privacy bugs — when storyvox starts probing arbitrary `*.local` hosts it has crossed a line we don't need to cross. P1 candidate if and only if JP asks.

A thin `PalaceReachability` class in `:source-mempalace` exposes `Flow<ReachabilityState>`, recomputed on:

- Settings change (host edited).
- Tab open in Browse.
- Pull-to-refresh.
- App foreground (so commute-leave-home flips the flag).

```kotlin
sealed class ReachabilityState {
    object NotConfigured : ReachabilityState()
    data class Reachable(val daemonVersion: String) : ReachabilityState()
    data class Unreachable(val reason: String) : ReachabilityState()
}
```

### Network timeouts and cancellation

- **Connect timeout**: 1.5s. The daemon is on the LAN; if the TCP handshake doesn't land, we're not on LAN.
- **Read timeout**: 30s for `/list` and `/graph` (large palaces — `/graph` cold-starts at ~30s on the canonical palace), 15s for `/get_drawer`, 5s for `/health`.
- All `suspend` calls are cancellable via OkHttp `Call.cancel()` from inside `suspendCancellableCoroutine`. Same pattern as `:source-github`'s `GitHubApi.get()`.

### LAN-only enforcement (defense in depth)

The host field accepts any value the user types — but at runtime, `MemPalaceSource` rejects any URL whose authority resolves to a public IP. The check is:

1. Parse the user host as `URI`. Reject if scheme is `https` for now (LAN palace-daemons run plain HTTP — JP's reverse proxy at `palace.jphe.in` is the only HTTPS option and that's still on the home network behind Cloudflare). HTTP-only is documented; future https support is straightforward.
2. Resolve to InetAddress; reject if `!isSiteLocalAddress() && !isLoopbackAddress() && !endsWith(".local")` and not specifically `palace.jphe.in` (allowlisted because it's JP's reverse proxy onto the LAN palace).

Result: typing `https://palace.example.com` into the host field doesn't suddenly start sending JP's palace data over the open internet to anyone's host.

## `FictionSource` implementation map

`FictionSource` has 9 abstract methods. Mapping them to palace operations:

| Method | Implementation |
|---|---|
| `id` | `"mempalace"` |
| `displayName` | `"Memory Palace"` |
| `popular(page)` | `/graph` → top-N rooms by drawer-count, mapped to `FictionSummary`. Page 1 returns the top 50; page>1 returns empty. |
| `latestUpdates(page)` | `/list?limit=50&offset=0` then group by `wing/room`, dedupe, sort by max(`filed_at`) descending. Cached 5min. |
| `byGenre(genre, page)` | `genre` here is the **wing name**. Returns rooms in that wing. (We co-opt the `genre` parameter; the user-facing label is "Wing".) |
| `genres()` | `/graph` → `wings.keys.sorted()`. The wing list IS the genre list. |
| `search(query)` | v1: `FictionResult.Success(emptyList())` — Search tab hidden in Browse → Palace. P1: `/search?q=…` then group hits by `wing/room`. |
| `fictionDetail(fictionId)` | Parse → `(wing, room)`. `/list?wing=…&room=…&limit=200&offset=0` to page through and build the `chapters` list. Title = `"<wing> / <room>"`. Author = `"MemPalace"`. Chapter count from `count` field. |
| `chapter(fictionId, chapterId)` | Extract `drawer_id` from chapterId. Call `/mcp` → `mempalace_get_drawer({drawer_id})`. Body is the verbatim `content`. Title is derived from `metadata.source_file` (basename) or first line of content. |
| `followsList(page)` | `FictionResult.AuthRequired` — palace doesn't have a follows concept. P1 candidate: synthesize follows from drawers tagged in some "favorite" wing or use a local follows-only set. |
| `setFollowed(fictionId, followed)` | Same — `FictionResult.AuthRequired` in v1. |
| `latestRevisionToken(fictionId)` | `/list?wing=…&room=…&limit=1&offset=0` returns the most recent drawer's `drawer_id`. Tokenize as `last_drawer_id`. The poll worker compares; if unchanged, skip the heavier `fictionDetail` round-trip. |

### Mapping a drawer to a chapter

`mempalace_get_drawer` returns:

```json
{
  "drawer_id": "drawer_projects_configuration_6383f16abfd2a6e1af8acf5a",
  "content": "Projects Overview\n=================\n\n3DPrinting/\n  3D printing files (.3mf, .gcode)...",
  "wing": "projects",
  "room": "configuration",
  "metadata": {
    "filed_at": "2026-04-09T19:19:20.970872",
    "source_file": "OVERVIEW.txt",
    "chunk_index": 0,
    "added_by": "mempalace",
    "source_mtime": 1773436510.5132234
  }
}
```

The chapter content is the `content` field, verbatim. Two transforms before handing to the reader:

1. **Title derivation.** The palace doesn't store chapter titles; we derive one. Priority:
   - `metadata.source_file` basename, prettified (`"OVERVIEW.txt"` → `"Overview"`). Strips extension, replaces `_-` with spaces, title-cases.
   - Else, the first heading in the content (`# Foo`, `Foo\n===`, etc.).
   - Else, the first 60 chars of content with ellipsis.
   - Combined with `chunk_index` when > 0: `"Overview (part 2)"` so multi-chunk source files read as a sequence.
2. **Plaintext rendering.** Drawer content is verbatim and may be markdown, plaintext, or code. We don't render markdown — the reader is TTS-first. If the content is mostly code (heuristic: ≥60% lines start with whitespace + a leading `#`/`//`/`def`/`class`/`function`), we tag it as `ChapterContent.kind = CODE` so the reader can choose to skip code blocks or read them at slower pace. Otherwise default `kind = NARRATIVE`. (Investigate how `:source-github` markdown renderer tags this; pattern likely already exists.)

### Mapping a room to a `FictionSummary`

```kotlin
private fun roomToSummary(wing: String, room: String, drawerCount: Int): FictionSummary =
    FictionSummary(
        id = "mempalace:$wing/$room",
        sourceId = SourceIds.MEMPALACE,
        title = prettify(room),                 // "realmwatch" → "Realmwatch"; "claude_code_python" → "Claude Code Python"
        author = "MemPalace · ${prettify(wing)}",
        coverUrl = null,                         // P1 — could synthesize a cover from KG entities
        description = "$drawerCount entries from your palace.",
        tags = listOf(wing),                     // wing as a single tag = filterable
        status = FictionStatus.ONGOING,          // palace is always growing
        chapterCount = drawerCount,
        rating = null,
    )
```

`coverUrl = null` is fine — `FictionCoverThumb` already handles null and renders a brass-toned placeholder. (P1: synthesize palace-themed covers per wing — `/viz`-style dashboard SVG snapshots, perhaps.)

## Browse UX

### Tab structure inside Palace

`BrowseSourceKey.MemPalace` supports three tabs (mirrors GitHub which has `Popular / NewReleases / Search` minus BestRated):

| Tab | Maps to | Behaviour |
|---|---|---|
| **Wings** | `popular()` | Top-N rooms by drawer count. Cards show `<wing>/<room>` + count. The default landing tab. |
| **Recent** | `latestUpdates()` | Rooms ordered by most-recent drawer `filed_at`. Cached 5min. |
| **Search** | hidden in v1 | Wired to `/search` in P1; tab-row simply hides the Search tab when source is MemPalace and the relevant feature flag isn't set. |

The "By Wing" picker (= `byGenre(genre)`) surfaces through the **filter sheet**, not as a tab. Filter sheet:

- Single-select wing dropdown — populated from `genres()` = `/graph` wings list.
- "All wings" by default.
- Selecting a wing scopes the Wings + Recent tabs to that wing.

This matches how `:source-royalroad`'s genre-filter works conceptually and reuses the existing `BrowseFilterSheet` plumbing.

### Settings UI — "Memory Palace" section

Single section in `SettingsScreen.kt`, between "Sources" (where GitHub PAT lives) and "Reading":

```
Memory Palace
─────────────────────────────────────
Status: ✓ Connected to palace.local at 10.0.6.50
        Daemon v1.7.2, 151,892 entries

[ Palace host                          ] (text field — "10.0.6.50:8085" or "palace.local")
[ API key (optional)                   ] (password field, masked)
[Test connection]   [Clear]
```

Status pill:
- `✓ Connected to <host>` (green) when reachable.
- `⏵ Off home network` (amber) when configured but unreachable.
- `Set host to enable` (subdued) when not configured.

"Test connection" button forces a `/health` probe and a one-shot toast reflecting the result. "Clear" zeroes both fields and disables the source.

Persistence:
- Host: `PALACE_HOST` (string) in the regular `storyvox_settings` DataStore.
- API key: `palace.api_key` in `EncryptedSharedPreferences` (`storyvox.secrets`).

## Caching + offline behaviour

The repository layer already owns this — same caching rules as Royal Road and GitHub apply once the source is plumbed in:

- `FictionDetail` is cached in Room. Stale-while-revalidate.
- Chapter bodies are cached in Room. The reader pulls from cache first, refetches lazily.
- The `ChapterDownloadWorker` is reused — it just hits a different URL.
- **Offline reading works** for any drawer whose body has been cached, regardless of palace reachability. This is huge: JP can pre-cache a room ("Download all chapters of `projects/realmwatch`") then listen on the train.
- **Browse listings** are not cached locally beyond the in-memory paginator state — pulling to refresh re-hits `/graph` and `/list`. The off-LAN state shows the most recent successful Browse landing if available, else a "set host" or "off network" empty state.

The `latestRevisionToken` integration with `NewChapterPollWorker` means: on Wi-Fi, every `pollIntervalHours` (user-configurable, default 6), the worker calls `/list?wing=…&room=…&limit=1` per followed palace fiction and skips the heavier `fictionDetail` fetch if the token is unchanged. Cheap enough that it doesn't matter even on a slow palace.

## Failure modes + graceful degrade

| Failure | Behaviour |
|---|---|
| Daemon down (connection refused) | `FictionResult.NetworkError("Could not reach palace daemon")`. Browse → Palace shows empty state with "Reconnect on home network." |
| Daemon 503 (palace collection unavailable, e.g. mid-rebuild) | `NetworkError` with message echoing daemon's own. Pull-to-refresh retries. |
| Daemon 401 (wrong API key) | `AuthRequired` with message "API key rejected by palace daemon." Settings shows ✗ status. |
| Drawer not found (404) | `NotFound`. The reader shows the existing "chapter unavailable" state. Doesn't poison the fiction listing — adjacent chapters keep working. |
| Wing/room renamed on the palace | The fiction's persisted id breaks. `fictionDetail` returns `NotFound`; the user is offered an "Open the new location?" prompt that re-resolves via search. P1 polish — for v1 we accept that wing/room renames invalidate library entries (rare on JP's palace; renames are deliberate). |
| Network flap mid-fetch | OkHttp default retry on connection failure already retries once. Beyond that, `NetworkError` propagates. |
| Off-LAN entirely | `PalaceReachability` flips to `Unreachable`. Browse tab is disabled (greyed) at the source-picker level. Library entries with cached chapters keep playing. |
| Palace size > what we want to load | `/graph` returns the whole wings/rooms structure; for JP's palace that's ~36 wings × ~60 rooms = ~2K rows. Trivial JSON, no streaming needed. Larger palaces stay snappy because the daemon does the fan-out server-side. |

## Privacy

The palace contains JP's verbatim conversation transcripts, mined project notes, planning docs, and decisions — **all personal**. Treating it as a fiction source means storyvox will read it aloud over the device speakers. This is JP's personal device + personal palace, so no concern there, but two specific guards:

1. **No telemetry / analytics on palace content.** No drawer ids, content excerpts, or wing names in any log line that could leave the device. (Storyvox doesn't have telemetry today; we're not adding any here.)
2. **No clipboard, no share-sheet, no "open in browser" actions on a palace chapter.** The content is for listening only; existing reader actions like "copy passage" are reviewed and disabled for `sourceId == "mempalace"`. (P0 — this is a real edit in `FictionDetailScreen.kt` / reader.)
3. **Palace data is not exported in any of storyvox's existing export paths** (Library export, OPML, etc.) Where present, those filter on `sourceId != "mempalace"`. (Audit before merge.)

These three are the entire privacy surface; calling them out here so the reviewer doesn't have to dig.

## Build sequence

Each step is a small recoverable commit; they ship together as one PR closing #79.

1. **`:source-mempalace` module skeleton** — gradle wiring, `package` files, empty `MemPalaceSource` returning `NotImplementedError` for everything. Compiles. *No user-visible change.*
2. **`PalaceDaemonApi` + JSON models** — OkHttp client over the four endpoints we use, kotlinx.serialization models for `/graph`, `/list`, `/health`, and the `mempalace_get_drawer` MCP envelope. Unit-tested against mocked responses.
3. **`MemPalaceSource` impls** — `popular`, `latestUpdates`, `genres`, `byGenre`, `fictionDetail`, `chapter`, `latestRevisionToken`. `search` returns empty. `followsList`/`setFollowed` return `AuthRequired`. Unit-tested with `MockWebServer`.
4. **DI binding** — `MemPalaceModule.kt` contributes the source via `@IntoMap @StringKey(SourceIds.MEMPALACE)`. App build adds the dep.
5. **`PalaceReachability`** — `Flow<ReachabilityState>` driven by SettingsRepo + foreground + tab-open. Test with mocked `PalaceDaemonApi`.
6. **Settings UI** — "Memory Palace" section: host field, API-key field (encrypted), test-connection button, status pill.
7. **Browse integration** — add `BrowseSourceKey.MemPalace` to the segmented source picker. Tabs `Wings / Recent / (Search hidden)`. Filter sheet gets the wing dropdown.
8. **Off-LAN UX** — disable source picker entry when `Unreachable`; tab subtitle reads "Reconnect on home network."
9. **Privacy guards** — disable copy/share for `mempalace` chapters; audit export paths.
10. **Tests + spec verification** — `MemPalaceSource` MockWebServer suite; `MemPalaceIds` codec round-trip tests; `PalaceReachability` tests; settings persistence test. Spec verified by Copilot review.

Steps 1-6 are pure build-without-UI; step 7 makes the source visible; steps 8-9 are polish; step 10 is the ship gate.

## Tradeoffs called out

- **Synthetic fictions vs. authored fictions.** Royal Road and GitHub fictions are *authored* by humans and carry titles, descriptions, covers. Palace fictions are *synthesised* from scratch — their "title" is `"realmwatch"`, their "author" is `"MemPalace"`, no cover. The reader UI is unchanged but the cards in Browse will look spartan. Acceptable: this isn't a webnovel browser, it's a personal-substrate listening tool.
- **Read-only is a hard limit.** The original brief asked for save-to-palace, daily summaries, KG integration. None of those are in scope. JP reframed; we follow. P1 captures the deferred work.
- **Wing rename = library breakage.** If JP renames `projects/realmwatch` → `projects/realm_watch` on the palace side, every persisted library entry pointing at `mempalace:projects/realmwatch` becomes `NotFound`. We accept this for v1 — palace renames are rare and JP knows the link is data-coupled. P1 polish: redirect-on-NotFound by searching for the room under its new name.
- **No write parity with `:source-github`'s registry.** GitHub has a curated "registry" repo for featured fictions; the palace has no such concept. The Wings tab plays the same UX role — top-N most-loaded rooms by drawer count.
- **API-key encryption for a non-secret.** The daemon API key is technically a write-capable token, but for v1 we never call write endpoints, so even key leakage doesn't grant anything we couldn't already do read-only. We still encrypt to follow the existing pattern (RR cookies) and to forward-compat the eventual write story.
- **`/graph` cold-start at ~30s.** On a palace with no recent traffic, the first `/graph` call can take 30s while `mempalace_list_wings` warms up. We surface this by showing a skeleton state during the initial Browse → Palace load, not a spinner-forever. Subsequent loads are sub-second.
- **No streaming chapter list.** A 5,000-chapter room loads its full chapter list eagerly via paginated `/list` calls. We page through 200 at a time; 25 round-trips is unfortunate but only happens once per fiction-open. Reader's resume-from-position is per-chapter so the eager load doesn't gate playback. P1: server-side cursor or stream the listing in chunks behind a Flow.

## Decisions (so far)

- **2026-05-08 — Mapping.** Room = Fiction, Drawer = Chapter. (Above; rationale documented.)
- **2026-05-08 — `/graph` is the Browse API.** Drawer-count distribution drives the "Wings" tab; the tunnels and KG fields in `/graph` are ignored in v1.
- **2026-05-08 — LAN-only is hard.** No internet exposure, no auto-discovery, hostname allowlist guards against DNS misuse.
- **2026-05-08 — API key encrypted.** Same store as RR cookies; same rationale.
- **2026-05-08 — Read-only in v1.** Saves and writes are P1.
- **2026-05-08 — Hostname unencrypted.** It's a LAN host, not a secret.
- **2026-05-08 — No mDNS auto-discovery.** Manual host entry. Add only if/when JP asks.
- **2026-05-08 — Search tab hidden in Browse → Palace.** Surface in P1 once we figure out cross-room ranking.
- **2026-05-08 — Privacy guards: no copy, no share, no export, no telemetry on palace content.**

## P1+ — deferred work

In rough priority order:

1. **Save listening progress to palace.** When a user finishes a chapter, write a diary entry: "Listened to `<fictionId>:<chapterId>` for ~Nmin." Useful for "what did I listen to last week?" semantic search.
2. **Save chapter highlights.** Long-press a passage in the reader → "Save to palace" → writes to a `storyvox_highlights` drawer in `wing_realmwatch_listening`. Mirrors the original #79 brief's P0 — deferred as a follow-up.
3. **Daily listening summary worker.** Once-per-day diary entry summarising recent listening.
4. **Search tab.** Wire `/search?q=…` and surface hits grouped by `wing/room`.
5. **KG-driven cover art.** Synthesise covers per wing from KG entity sketches.
6. **Wing-rename redirect.** When `fictionDetail` returns `NotFound`, search for the room under its new name and offer to relink.
7. **Cross-room follows / collections.** "Subscribe" to a wing or a tag-set across rooms. Today's library row is one fiction = one room.
8. **mDNS auto-discovery.** Probe `_palace-daemon._tcp.local`. Only if and when JP asks.
9. **Palace as authored output sink.** The reverse direction: take a Royal Road fiction we've listened to and pipe annotations / highlights / personal commentary back into the palace as a derivative wing. Strong overlap with #1, possibly the same feature.

## Open questions

None blocking implementation. JP's confirmation needed on:

1. The room-as-fiction mapping (vs. wing-as-fiction or drawer-as-fiction). Recommendation: **room-as-fiction**, justified above.
2. The hidden Search tab in v1. Recommendation: **hide**, surface in P1.
3. The privacy guards (no copy, no share, no export). Recommendation: **enforce**.

Default is to ship the recommendations unless JP overrides.

---

## Appendix A — palace-daemon endpoint examples

`GET /graph` (sample, abbreviated):

```json
{
  "wings": {
    "projects": 38450,
    "bestiary": 65893,
    "claude_code_python": 2366,
    "realmwatch": 5614,
    "techempower": 1498
  },
  "rooms": [
    {"wing": "projects", "rooms": {"realmwatch": 5614, "architecture": 10787, "technical": 54160}},
    {"wing": "bestiary", "rooms": {"creatures": 12000, "lore": 8000}}
  ],
  "tunnels": [],
  "kg_entities": [],
  "kg_triples": [],
  "kg_stats": {"entities": 0, "triples": 0}
}
```

`GET /list?wing=projects&room=realmwatch&limit=3` (sample):

```json
{
  "drawers": [
    {
      "drawer_id": "drawer_projects_realmwatch_a1b2c3...",
      "wing": "projects",
      "room": "realmwatch",
      "content_preview": "RealmWatch is a fantasy-themed observability dashboard for JP's home..."
    }
  ],
  "count": 1,
  "offset": 0,
  "limit": 3
}
```

`POST /mcp` with `mempalace_get_drawer` (sample):

```json
{
  "drawer_id": "drawer_projects_realmwatch_a1b2c3...",
  "content": "RealmWatch is a fantasy-themed observability dashboard for JP's home network. It tracks 20+ hosts via collectd...",
  "wing": "projects",
  "room": "realmwatch",
  "metadata": {
    "filed_at": "2026-04-12T08:14:33.221045",
    "source_file": "OVERVIEW.md",
    "chunk_index": 0,
    "added_by": "mempalace"
  }
}
```

## Appendix B — module dependency graph

```
:app
  ├── :source-royalroad
  ├── :source-github
  └── :source-mempalace      ← new
        └── :core-data       (existing — FictionSource, SourceIds, models)
```

Same leaf shape as the other two source modules. No source module depends on any other source module.

## Appendix C — storage keys added

```
storyvox_settings (DataStore):
  pref_palace_host:        String   "" (default disables source)

storyvox.secrets (EncryptedSharedPreferences):
  palace.api_key:          String   "" (default — daemon may accept unauthenticated)
```

Two keys total. Both default to empty; both are user-controlled in Settings → Memory Palace.
