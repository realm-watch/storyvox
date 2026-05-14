package `in`.jphe.storyvox.source.notion.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #233 — abstraction over the Notion source's persistent config.
 *
 * Three knobs:
 *   - **databaseId**: the Notion database that storyvox treats as a
 *     fiction catalog. Each page in this database becomes one fiction.
 *     Defaults to [NotionDefaults.TECHEMPOWER_DATABASE_ID] per #390 —
 *     fresh installs land pre-pointed at the techempower.org content DB.
 *   - **apiToken**: a Notion *Internal Integration* PAT (starts with
 *     `ntn_` or `secret_`). Stored in EncryptedSharedPreferences. Empty
 *     means the source is read-disabled and Browse → Notion renders an
 *     empty-state with a "configure token" CTA. Notion's REST API
 *     requires auth on every call — there is no anonymous tier.
 *   - **baseUrl**: configurable for testing; defaults to api.notion.com.
 *
 * Implementation lives in :app on top of DataStore + the shared
 * `storyvox.secrets` EncryptedSharedPreferences — same pattern as
 * [`in`.jphe.storyvox.source.outline.config.OutlineConfig].
 *
 * The source module stays free of Android Preferences plumbing so the
 * leaf-source architecture (source modules don't depend on :app) holds.
 */
interface NotionConfig {
    /** Hot stream of the current config state. */
    val state: Flow<NotionConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): NotionConfigState
}

/**
 * One Notion config state. Empty [apiToken] disables the source
 * entirely — Notion's API has no anonymous tier.
 */
data class NotionConfigState(
    /** The Notion database id the source surfaces as the Browse catalog.
     *  Hyphenated (8-4-4-4-12) or compact (32 hex chars) — Notion accepts
     *  both. Defaults to [NotionDefaults.TECHEMPOWER_DATABASE_ID] for #390. */
    val databaseId: String = NotionDefaults.TECHEMPOWER_DATABASE_ID,
    /** PAT-style "Internal Integration Token" from
     *  notion.so/my-integrations. Empty disables the source. Stored
     *  encrypted; never exposed to the UI as a readable string (the
     *  UiSettings projection only carries a `tokenConfigured: Boolean`). */
    val apiToken: String = "",
    /** Notion REST API base URL. Defaults to api.notion.com; overridable
     *  for test infra without rewriting the source. */
    val baseUrl: String = NotionDefaults.BASE_URL,
    /** Notion REST API version header — pinned in the source rather than
     *  the user config so storyvox + Notion never drift apart silently. */
    val apiVersion: String = NotionDefaults.API_VERSION,
) {
    /** True when the source can make API calls. Token presence is the
     *  one hard gate — Notion has no anonymous tier. */
    val isConfigured: Boolean
        get() = apiToken.isNotBlank() && databaseId.isNotBlank()
}
