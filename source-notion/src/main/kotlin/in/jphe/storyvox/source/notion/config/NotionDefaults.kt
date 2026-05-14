package `in`.jphe.storyvox.source.notion.config

/**
 * Issue #390 — baked-in defaults for the Notion fiction backend.
 *
 * [TECHEMPOWER_DATABASE_ID] points at TechEmpower's **Resources**
 * Notion database — the searchable database of free tech resources
 * for individuals with low income, their families, and nonprofit
 * organizations. The id is sourced from
 * [`techempower/site.config.ts`](https://github.com/techempower-org/techempower.org)
 * `pageUrlOverrides` line 47–48 (`'/resources': '2a3d7068…'`), which
 * is what the techempower.org website hits when a user navigates to
 * `/resources` — the same id is queryable as a Notion database via
 * the official REST API.
 *
 * Why a database (not a page):
 *  - Storyvox's [`:source-notion`] queries `databases/query/{id}`.
 *    Notion's data model distinguishes page-blocks from database-
 *    blocks; the website's `rootNotionPageId` (`0959e445…`) is the
 *    Guides *page* and would 404 against the database endpoint.
 *    `2a3d7068…` is a real database — the Resources collection
 *    underneath the root page — and that's what the API can read.
 *  - Existing users with a non-default databaseId persisted keep
 *    their value; this default only applies on fresh installs.
 *
 * Auth caveat: storyvox uses an integration-token PAT model. To
 * read TechEmpower's Resources database the user must have a Notion
 * integration shared with the database. The current `:source-notion`
 * UX assumes the user pastes their own integration token; for the
 * "techempower content out of the box" experience to fully work
 * without setup, TechEmpower would need to either share the
 * Resources DB publicly with a known token, or ship a bundled
 * read-only token (issue #393 tracks that decision).
 *
 * Format note: Notion accepts both the hyphenated UUID form
 * (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) and the compact 32-hex
 * form in its REST API. We store + emit the compact form here.
 */
object NotionDefaults {
    /**
     * The TechEmpower Resources database id, from
     * `techempower/site.config.ts` line 48 (`pageUrlOverrides`,
     * `/resources` route). Searchable database of free tech resources
     * for low-income families, surfaced as the storyvox Notion
     * backend's default content target on fresh installs (#390).
     */
    const val TECHEMPOWER_DATABASE_ID: String =
        "2a3d706803c649409e74e9ce5ccd4c4b"

    /**
     * Issue #393 — TechEmpower's root **page** id (the parent of the
     * Resources database and all 8 Guide pages). Used in
     * `NotionMode.ANONYMOUS_PUBLIC` mode: storyvox loads this page's
     * child blocks via the unofficial `loadPageChunk` endpoint and
     * expands collection_view children + page children into individual
     * fictions. Sourced from `techempower/site.config.ts`
     * `rootNotionPageId` (line 5) — the same id `techempower.org` uses
     * as its top-level Notion page.
     */
    const val TECHEMPOWER_ROOT_PAGE_ID: String =
        "0959e44599984143acabc80187305001"

    /** Notion REST API host. */
    const val BASE_URL: String = "https://api.notion.com"

    /**
     * Issue #393 — base URL for the *unofficial* Notion API
     * (`www.notion.so/api/v3`). This is the same surface
     * `react-notion-x`'s `notion-client` package hits — it's how the
     * notion.site renderer works without a token. Notion serves
     * `loadPageChunk`, `queryCollection`, `syncRecordValuesMain`, and
     * `getPublicPageData` on this host without `Authorization`.
     *
     * Caveat: this endpoint is undocumented and Notion may change it.
     * We pin to the v3 path and add structured error decoding so we
     * surface useful messages if Notion shifts shape.
     */
    const val UNOFFICIAL_BASE_URL: String = "https://www.notion.so/api/v3"

    /**
     * Notion REST API version pin. Notion's API requires a
     * `Notion-Version` header on every request; we pin to the
     * 2022-06-28 stable contract because (a) every endpoint storyvox
     * needs (`databases/query`, `blocks/{id}/children`, `pages/{id}`)
     * is stable there, and (b) Notion adds breaking changes silently
     * on the latest version moniker. Pin + upgrade-on-purpose beats
     * follow-the-trunk.
     */
    const val API_VERSION: String = "2022-06-28"

    /**
     * User-Agent used by all storyvox→Notion HTTP traffic. Identifies
     * the project + gives a contact path so Notion's abuse / rate-limit
     * tooling can route concerns somewhere useful.
     */
    const val USER_AGENT: String =
        "storyvox-notion/1.0 (+https://github.com/techempower-org/storyvox)"

    /**
     * Issue #393 — chapter list for the single TechEmpower fiction in
     * anonymous mode. Browse → Notion surfaces one tile
     * ("TechEmpower.org"); this list defines its chapter spine.
     *
     * Order matches `site.config.ts` `navigationLinks`: Guides
     * (the root page itself), Resources (the database), About, Donate.
     * Each entry resolves to either a Notion page (rendered via the
     * page's own block content) or a Notion collection (rendered as
     * an overview list of row titles).
     *
     * Adding a new chapter — e.g. the non-discrimination policy at
     * `cdbe9906ae2441a1a9bb3aec601a5a6c` — is a one-line append. The
     * delegate doesn't hardcode chapter count anywhere; this list IS
     * the schema.
     */
    internal val techempowerChapters: List<`in`.jphe.storyvox.source.notion.ChapterSpec> = listOf(
        `in`.jphe.storyvox.source.notion.ChapterSpec.Page(
            title = "Guides",
            // The root page itself — its block content includes the
            // intro paragraphs, the 8 guide sub-page references, and
            // the link to Resources. Internal `header` blocks render
            // as `<h1>` inline so the chapter reader sees them as
            // sub-headings within "Guides".
            pageId = TECHEMPOWER_ROOT_PAGE_ID,
        ),
        `in`.jphe.storyvox.source.notion.ChapterSpec.Collection(
            title = "Resources",
            blockId = TECHEMPOWER_DATABASE_ID,
        ),
        `in`.jphe.storyvox.source.notion.ChapterSpec.Page(
            title = "About",
            pageId = "dbf0ddece2ce468fb2bf9049e6322e8a",
        ),
        `in`.jphe.storyvox.source.notion.ChapterSpec.Page(
            title = "Donate",
            pageId = "59d8a4dab0cc484f8b044d33f240ce1d",
        ),
    )
}
