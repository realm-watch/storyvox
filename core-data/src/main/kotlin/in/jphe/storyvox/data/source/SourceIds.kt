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
}
