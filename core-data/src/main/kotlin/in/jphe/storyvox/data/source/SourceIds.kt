package `in`.jphe.storyvox.data.source

/**
 * Canonical sourceId literals shared across the source modules, the
 * UrlRouter, the Hilt MapBinding @StringKey annotations, and any other
 * call site that needs to identify a source by its string key.
 *
 * Lives in :core-data so source modules and core consumers can both
 * depend on it without breaking the leaf-source architecture (source
 * modules don't depend on each other; they all depend on core-data).
 *
 * Adding a new source: add a new `const val` here, then use it at the
 * source's `FictionSource.id`, the corresponding Hilt `@StringKey`,
 * and any UrlRouter Match construction. Issue #57 tracks the
 * deduplication; this file is the single source of truth.
 */
object SourceIds {
    const val ROYAL_ROAD: String = "royalroad"
    const val GITHUB: String = "github"
    /** MemPalace — JP's local memory system, treated as a read-only
     *  fiction source. Spec: docs/superpowers/specs/2026-05-08-
     *  mempalace-integration-design.md (#79). */
    const val MEMPALACE: String = "mempalace"
    /** RSS / Atom feeds (#236) — user adds feed URLs (one fiction =
     *  one feed; each feed item = one chapter). Pure user-content
     *  backend; explicit author opt-in by definition (publishing RSS
     *  is a publishing protocol). */
    const val RSS: String = "rss"
    /** Local EPUB files (#235) — user picks a folder via SAF, the
     *  source enumerates .epub files there as fictions. Zero-network,
     *  user-owned-content backend. */
    const val EPUB: String = "epub"
    /** Outline (#245) — self-hosted wiki. User configures host +
     *  API token; collections become fictions, documents become
     *  chapters. Zero third-party ToS surface. */
    const val OUTLINE: String = "outline"
    /** Project Gutenberg (#237) — 70,000+ public-domain books via the
     *  Gutendex JSON catalog wrapper. Tap-to-add downloads the title's
     *  EPUB to local cache; chapter rendering reuses the EPUB parser
     *  from `:source-epub`. Most-legally-clean content backend in the
     *  storyvox source roster — PG actively encourages programmatic
     *  access (see their robot policy). */
    const val GUTENBERG: String = "gutenberg"
    /** Archive of Our Own (#381) — 14M+ fanfiction works via AO3's
     *  per-tag Atom feeds (catalog) + per-work EPUB downloads
     *  (content). Same architecture as [GUTENBERG] — discovery via a
     *  documented non-HTML surface, content via the official EPUB
     *  endpoint, no scraping. Default OFF on fresh installs because
     *  AO3 content can be Explicit-rated; opt-in from
     *  Settings → Library & Sync. */
    const val AO3: String = "ao3"
    /** Standard Ebooks (#375) — ~900 hand-curated, typographically
     *  polished public-domain classics from Project Gutenberg.
     *  Differentiates over plain PG via editorial polish (cover art,
     *  consistent typography, proofreading); same legal posture
     *  (every release dedicated to the public domain under CC0 1.0).
     *  Tap-to-add downloads the compatible EPUB to local cache and
     *  reuses the `:source-epub` parser for chapter rendering. */
    const val STANDARD_EBOOKS: String = "standardebooks"
    /** Wikipedia (#377) — first non-fiction long-form backend in the
     *  storyvox roster. Each Wikipedia article is one fiction; each
     *  top-level section (`<h2>`) within the article is one chapter
     *  (chapter 0 is the lead / "Introduction"). Sourced from the
     *  Wikimedia REST API at `<lang>.wikipedia.org`; the language
     *  code is user-configurable so the same module serves all
     *  language Wikipedias. */
    const val WIKIPEDIA: String = "wikipedia"
    /** Wikisource (#376) — Wikimedia project for transcribed public-
     *  domain texts (Shakespeare, classic novels, historical documents).
     *  Same API surface as Wikipedia (MediaWiki Action API + Parsoid
     *  HTML); chapter splitting prefers `/Subpage` walks when a work
     *  is structured that way and falls back to in-page heading splits
     *  for single-page works. Same CC0/public-domain legal posture as
     *  Project Gutenberg / Standard Ebooks. */
    const val WIKISOURCE: String = "wikisource"
    /** Radio (#417, generalized from #374's `:source-kvmr`) — live
     *  radio streams (curated KVMR/KQED/KCSB/KXPR/SomaFM seed list +
     *  user-starred Radio Browser imports). Each station is one
     *  fiction with one "Live" chapter; the chapter carries an
     *  AAC/MP3/HLS stream URL routed through Media3 / ExoPlayer (audio
     *  pipeline bypasses TTS — see issue #373). The CC0 Radio Browser
     *  directory (https://www.radio-browser.info/) powers the search
     *  surface so the long tail of community / college / public radio
     *  stations is reachable without storyvox needing to curate
     *  manually. */
    const val RADIO: String = "radio"

    /** Legacy alias for [RADIO] kept across one migration cycle so
     *  v0.5.20..0.5.31 persisted KVMR fictions (rows with
     *  `sourceId = "kvmr"`) continue to resolve after the
     *  :source-kvmr → :source-radio rename. The matching `@StringKey`
     *  binding in `:source-radio`'s Hilt module routes both ids to the
     *  same [`RadioSource`][in.jphe.storyvox.source.radio.RadioSource]
     *  instance. A follow-up release can drop this once one full
     *  release cycle has elapsed; until then the alias is load-bearing
     *  for library-shelf / playback-position survival. */
    const val KVMR: String = "kvmr"
    /** Notion (#233) — Notion databases as a fiction backend. Each
     *  database row is one fiction; each page's top-level `heading_1`
     *  boundary splits it into chapters. Requires a Notion Internal
     *  Integration Token (PAT). Default database id points at the
     *  techempower.org content database (#390) so a fresh install
     *  surfaces TechEmpower content without configuration once the
     *  user supplies the integration token. */
    const val NOTION: String = "notion"
    /** Hacker News (#379) — front-page stories + Ask HN / Show HN +
     *  Algolia-backed search. Each story = one fiction with exactly
     *  one chapter ("listen to one HN thread as an episode"). Free,
     *  no-auth JSON via the Firebase API (https://hacker-news.firebaseio.com/v0/).
     *  Default OFF: tech-news is a distinct interest from fiction
     *  backends and the existing picker shouldn't surface it on
     *  every fresh install. */
    const val HACKERNEWS: String = "hackernews"
    /** arXiv (#378) — open-access academic papers backend. Second non-
     *  fiction-shaped source after [WIKIPEDIA]. Each arXiv paper is one
     *  fiction; the v1 chapter shape is a single "Abstract" chapter
     *  rendered from the paper's `arxiv.org/abs/<id>` HTML page (title
     *  + author byline + subjects + comments + abstract paragraph).
     *  Full-PDF text extraction is an explicit follow-up scope cut from
     *  #378's acceptance criteria. Default category for the browse
     *  landing is `cs.AI`; a category picker is a v2 surface. */
    const val ARXIV: String = "arxiv"
    /** PLOS / Public Library of Science (#380) — open-access peer-
     *  reviewed science. Each article is one fiction (DOI = id);
     *  v1 renders the abstract + first ~3 body sections as a single
     *  chapter. Same architectural seam as Wikipedia — Solr-backed
     *  search API + per-article HTML pages, all CC-licensed content.
     *  Default OFF on fresh installs (academic content is opt-in;
     *  not what a fresh-install user expects in the picker until
     *  they go looking for it). */
    const val PLOS: String = "plos"
    /** Discord (#403) — first chat-platform backend in the storyvox
     *  roster. Mapping: server → top-level filter, channel → one
     *  fiction, message → one chapter (optionally coalescing
     *  consecutive same-author messages inside a configurable
     *  time window). Auth model is user-supplied bot token
     *  (PAT-style) — the user creates a Discord application,
     *  generates a bot token, invites their bot to the target
     *  server with `READ_MESSAGE_HISTORY` scope, and pastes the
     *  token in Settings. No bundled default token, no auto-join,
     *  no selfbot/user-token paths (banned by Discord ToS).
     *  Default OFF on fresh installs because bot-token onboarding
     *  is high-friction and Discord is a private workspace, not a
     *  public catalog. */
    const val DISCORD: String = "discord"
    /** Telegram (#462) — fourth chat-platform backend in the
     *  storyvox roster. Mapping: public channel → one fiction,
     *  channel post → one chapter. Auth model is user-supplied
     *  Bot API token (created via @BotFather, one-time setup).
     *  The bot has to be invited as a member of any public
     *  channel the user wants to read. No bundled default token,
     *  no auto-join, no MTProto user-side path (private DMs /
     *  private groups deferred to a sibling issue). Default OFF
     *  on fresh installs because bot-token onboarding is
     *  high-friction. v1 ships no search (Bot API has no
     *  full-text search endpoint). */
    const val TELEGRAM: String = "telegram"
    /** Readability4J catch-all (#472, magic-link audiobook) — the
     *  always-on last-resort matcher for the paste-anything flow. Any
     *  HTTP(S) URL that none of the 17 specialized backends claim
     *  falls through to here, where Readability4J's Mozilla-Readability
     *  port extracts the article body and renders it as a single-
     *  chapter fiction. Confidence is pinned to 0.1f in
     *  [UrlMatcher][in.jphe.storyvox.data.source.UrlMatcher], so this
     *  source never wins when a specialized backend also claims the URL
     *  — it's a safety net, not a competitor. */
    const val READABILITY: String = "readability"
}
