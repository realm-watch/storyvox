package `in`.jphe.storyvox.llm

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted storage for LLM-provider API keys. Reuses the same
 * `EncryptedSharedPreferences` instance bound in `:core-data`'s
 * `DataModule.provideEncryptedPrefs` — the one that holds Royal Road
 * cookies (and, post-#86, the GitHub PAT). Tink-backed AES-256-GCM.
 *
 * Methods are deliberately per-provider rather than a single
 * generic `get(provider: ProviderId)` because each provider's
 * "credential" has a different shape — Claude/OpenAI/Vertex/Foundry
 * are single API keys; Bedrock is a key + secret pair; Teams is a
 * bearer token that may need refresh tracking. The per-method shape
 * keeps the type at each call site honest.
 *
 * Spec-only providers (Bedrock / Vertex / Foundry / Teams) get
 * methods now so that the encrypted-prefs key namespace is stable
 * — when those provider classes ship, they call existing methods
 * rather than introducing new ones.
 *
 * **The API key is NEVER logged.** The class itself doesn't log;
 * the OkHttp logging interceptor in production redacts both
 * `x-api-key` and `Authorization` headers.
 */
@Singleton
open class LlmCredentialsStore @Inject constructor(
    private val prefs: SharedPreferences,
) {

    // ── Claude direct (PR-1) ───────────────────────────────────────────
    open fun claudeApiKey(): String? = prefs.getString(KEY_CLAUDE, null)
    open fun setClaudeApiKey(key: String) =
        prefs.edit { putString(KEY_CLAUDE, key) }
    open fun clearClaudeApiKey() = prefs.edit { remove(KEY_CLAUDE) }
    open val hasClaudeKey: Boolean get() = claudeApiKey() != null

    // ── OpenAI direct (PR-1) ──────────────────────────────────────────
    open fun openAiApiKey(): String? = prefs.getString(KEY_OPENAI, null)
    open fun setOpenAiApiKey(key: String) =
        prefs.edit { putString(KEY_OPENAI, key) }
    open fun clearOpenAiApiKey() = prefs.edit { remove(KEY_OPENAI) }
    open val hasOpenAiKey: Boolean get() = openAiApiKey() != null

    // ── Spec-only — wired now so the prefs layout doesn't change
    // when these providers ship. ───────────────────────────────────────
    open fun bedrockAccessKey(): String? = prefs.getString(KEY_BEDROCK_ACCESS, null)
    open fun bedrockSecretKey(): String? = prefs.getString(KEY_BEDROCK_SECRET, null)
    open fun setBedrockKeys(access: String, secret: String) = prefs.edit {
        putString(KEY_BEDROCK_ACCESS, access)
        putString(KEY_BEDROCK_SECRET, secret)
    }
    open fun clearBedrockKeys() = prefs.edit {
        remove(KEY_BEDROCK_ACCESS); remove(KEY_BEDROCK_SECRET)
    }

    open fun vertexApiKey(): String? = prefs.getString(KEY_VERTEX, null)
    open fun setVertexApiKey(key: String) =
        prefs.edit { putString(KEY_VERTEX, key) }
    open fun clearVertexApiKey() = prefs.edit { remove(KEY_VERTEX) }
    open val hasVertexKey: Boolean get() = vertexApiKey() != null

    open fun foundryApiKey(): String? = prefs.getString(KEY_FOUNDRY, null)
    open fun setFoundryApiKey(key: String) =
        prefs.edit { putString(KEY_FOUNDRY, key) }
    open fun clearFoundryApiKey() = prefs.edit { remove(KEY_FOUNDRY) }
    open val hasFoundryKey: Boolean get() = foundryApiKey() != null

    open fun teamsBearerToken(): String? = prefs.getString(KEY_TEAMS, null)
    open fun setTeamsBearerToken(token: String) =
        prefs.edit { putString(KEY_TEAMS, token) }
    open fun clearTeamsBearerToken() = prefs.edit { remove(KEY_TEAMS) }
    open val hasTeamsToken: Boolean get() = teamsBearerToken() != null

    /** Anthropic Teams refresh token (#181). Stored alongside the
     *  bearer so the provider can transparently refresh on expiry
     *  without bouncing the user back through the browser. */
    open fun teamsRefreshToken(): String? = prefs.getString(KEY_TEAMS_REFRESH, null)
    open fun setTeamsRefreshToken(token: String) =
        prefs.edit { putString(KEY_TEAMS_REFRESH, token) }
    open fun clearTeamsRefreshToken() = prefs.edit { remove(KEY_TEAMS_REFRESH) }

    /** Epoch millis when the bearer token expires. Zero / missing means
     *  "unknown" — caller falls back to using the bearer until the API
     *  rejects it with 401 and then runs the refresh flow. */
    open fun teamsExpiresAt(): Long = prefs.getLong(KEY_TEAMS_EXPIRES_AT, 0L)
    open fun setTeamsExpiresAt(epochMillis: Long) =
        prefs.edit { putLong(KEY_TEAMS_EXPIRES_AT, epochMillis) }
    open fun clearTeamsExpiresAt() = prefs.edit { remove(KEY_TEAMS_EXPIRES_AT) }

    /** Granted scopes — surfaced on the "Signed in" Settings row. */
    open fun teamsScopes(): String? = prefs.getString(KEY_TEAMS_SCOPES, null)
    open fun setTeamsScopes(scopes: String) =
        prefs.edit { putString(KEY_TEAMS_SCOPES, scopes) }
    open fun clearTeamsScopes() = prefs.edit { remove(KEY_TEAMS_SCOPES) }

    /** Persist the entire Teams session in a single atomic edit. */
    open fun setTeamsSession(
        bearer: String,
        refreshToken: String?,
        expiresAtEpochMillis: Long,
        scopes: String,
    ) = prefs.edit {
        putString(KEY_TEAMS, bearer)
        if (refreshToken != null) putString(KEY_TEAMS_REFRESH, refreshToken)
        putLong(KEY_TEAMS_EXPIRES_AT, expiresAtEpochMillis)
        putString(KEY_TEAMS_SCOPES, scopes)
    }

    /** Forget the Anthropic Teams session entirely. */
    open fun clearTeamsSession() = prefs.edit {
        remove(KEY_TEAMS)
        remove(KEY_TEAMS_REFRESH)
        remove(KEY_TEAMS_EXPIRES_AT)
        remove(KEY_TEAMS_SCOPES)
    }

    /** Wipe every provider's credentials. The "Forget all AI
     *  settings" button. */
    open fun clearAll() = prefs.edit {
        remove(KEY_CLAUDE)
        remove(KEY_OPENAI)
        remove(KEY_BEDROCK_ACCESS); remove(KEY_BEDROCK_SECRET)
        remove(KEY_VERTEX); remove(KEY_FOUNDRY)
        remove(KEY_TEAMS); remove(KEY_TEAMS_REFRESH)
        remove(KEY_TEAMS_EXPIRES_AT); remove(KEY_TEAMS_SCOPES)
    }

    /**
     * Test-only constructor that bypasses SharedPreferences entirely.
     * Subclasses override the per-provider getters/setters to back
     * keys with whatever is convenient (a `MutableMap`).
     */
    protected constructor() : this(NullSharedPreferences)

    companion object {
        /** Test-only factory — returns an instance whose every
         *  read returns null and every write is a no-op. Tests of
         *  components that consume [LlmCredentialsStore] but don't
         *  exercise its storage layer can use this instead of
         *  building a Robolectric/EncryptedSharedPreferences. */
        fun forTesting(): LlmCredentialsStore = LlmCredentialsStore(NullSharedPreferences)

        // Encrypted-prefs key namespace. Stays stable across builds —
        // changing any of these would orphan an existing user's keys.
        internal const val KEY_CLAUDE = "llm_api_key:claude"
        internal const val KEY_OPENAI = "llm_api_key:openai"
        internal const val KEY_BEDROCK_ACCESS = "llm_api_key:bedrock_access"
        internal const val KEY_BEDROCK_SECRET = "llm_api_key:bedrock_secret"
        internal const val KEY_VERTEX = "llm_api_key:vertex"
        internal const val KEY_FOUNDRY = "llm_api_key:foundry"
        internal const val KEY_TEAMS = "llm_api_key:teams"
        internal const val KEY_TEAMS_REFRESH = "llm_api_key:teams:refresh"
        internal const val KEY_TEAMS_EXPIRES_AT = "llm_api_key:teams:expires_at"
        internal const val KEY_TEAMS_SCOPES = "llm_api_key:teams:scopes"
    }
}

/** A SharedPreferences that does nothing — every method returns the
 *  default value or no-ops. Used by the test-only constructor of
 *  [LlmCredentialsStore] when the test subclasses and overrides the
 *  high-level API. */
@Suppress("UnsafeOptInUsageError")
internal object NullSharedPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun edit(): SharedPreferences.Editor = NullEditor
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

@Suppress("UnsafeOptInUsageError")
internal object NullEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
    override fun remove(key: String?): SharedPreferences.Editor = this
    override fun clear(): SharedPreferences.Editor = this
    override fun commit(): Boolean = true
    override fun apply() {}
}
