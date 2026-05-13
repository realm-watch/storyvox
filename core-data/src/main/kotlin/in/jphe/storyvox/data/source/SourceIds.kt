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
}
