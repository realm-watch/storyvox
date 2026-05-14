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

    /** Notion REST API host. */
    const val BASE_URL: String = "https://api.notion.com"

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
}
