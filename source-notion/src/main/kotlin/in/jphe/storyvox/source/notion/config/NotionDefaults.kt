package `in`.jphe.storyvox.source.notion.config

/**
 * Issue #390 — baked-in defaults for the Notion fiction backend.
 *
 * [TECHEMPOWER_DATABASE_ID] is the **placeholder constant** for the
 * techempower.org content Notion database. The literal value below is
 * a TODO — JP fills in the actual database id before final release
 * (the database has to be created + shared with the storyvox
 * integration first; the id format is the 32-character hex string from
 * the database URL, with or without hyphens).
 *
 * Why ship with a placeholder rather than waiting:
 *  - Every other piece of the Notion backend (#233 — schema, API
 *    client, UI, settings plumbing) can land independently and
 *    bake/test against any DB id that exists in the user's workspace.
 *  - When the real techempower.org database id materializes, it's a
 *    one-line patch to this file — no architectural surgery.
 *  - The default applies only on **fresh installs**. Existing users
 *    who already have a non-default databaseId persisted keep it.
 *
 * Format note: Notion accepts both the hyphenated UUID form
 * (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) and the compact 32-hex
 * form in its REST API. We store + emit the compact form here.
 */
object NotionDefaults {
    /**
     * **TODO** — replace with the real techempower.org content database
     * id once the database is created + shared with the storyvox
     * Notion integration. The string below is intentionally invalid
     * (zeros) so a fresh install with no override hits a clean 404
     * empty-state rather than silently fetching from a typo'd DB.
     */
    const val TECHEMPOWER_DATABASE_ID: String =
        "TODO_FILL_IN_TECHEMPOWER_DATABASE_ID"

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
