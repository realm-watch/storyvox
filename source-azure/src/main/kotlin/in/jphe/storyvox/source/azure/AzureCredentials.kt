package `in`.jphe.storyvox.source.azure

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BYOK store for the user's Azure Speech subscription key + region.
 * Reuses the encrypted-prefs instance bound by `:core-data`'s
 * `DataModule.provideEncryptedPrefs` — the same Tink-backed AES-256-GCM
 * container that holds Royal Road cookies, the GitHub PAT, and the
 * LLM provider keys.
 *
 * Mirrors [in.jphe.storyvox.llm.LlmCredentialsStore]'s shape
 * deliberately so a future Settings refactor that consolidates "AI
 * keys" + "Cloud TTS keys" can hoist them into a shared base without
 * a migration. The prefs key namespace is independent — no collision
 * risk — so the LLM and Azure stores can also live as siblings
 * indefinitely.
 *
 * **The API key is NEVER logged.** The class itself doesn't log;
 * the OkHttp logging interceptor in [AzureSpeechClient] redacts the
 * `Ocp-Apim-Subscription-Key` header.
 *
 * **Threat model.** Same as the RR cookie / LLM keys: a rooted device
 * or backup-extracted device can read the encrypted prefs file. We
 * accept this — it's the storyvox-wide bar, and the alternative
 * (server-held key + storyvox-issued token) is the proxy mode Solara's
 * spec defers indefinitely.
 */
@Singleton
open class AzureCredentials @Inject constructor(
    private val prefs: SharedPreferences,
) {

    /** Current subscription key, or null if none persisted. */
    open fun key(): String? = prefs.getString(KEY_AZURE_KEY, null)

    /** Current region as an [AzureRegion]; falls back to the default
     *  if the persisted id doesn't match the curated set (e.g. user
     *  pasted a raw region id via the "Other" affordance, or a build
     *  rolled back the AzureRegion enum). The raw string is still
     *  available via [regionId] for the rare case where the curated
     *  list lags Azure's regional rollout. */
    open fun region(): AzureRegion =
        prefs.getString(KEY_AZURE_REGION, null)
            ?.let(AzureRegion::byId)
            ?: AzureRegion.DEFAULT

    /** Raw region id as persisted — falls back to the default region
     *  id when nothing is set. Use this when building the endpoint URL;
     *  it accepts a raw, non-curated region id ("centralus") that
     *  [region] would silently map to default. */
    open fun regionId(): String =
        prefs.getString(KEY_AZURE_REGION, null) ?: AzureRegion.DEFAULT.id

    /** Persist a new key. The Settings UI (PR-3) is responsible for
     *  trimming whitespace before calling this — an Azure key is a
     *  hex string with no internal spaces. */
    open fun setKey(key: String) =
        prefs.edit { putString(KEY_AZURE_KEY, key) }

    /** Persist a new region. Accepts a raw id so the "Other" affordance
     *  in Settings can save a region that isn't in the curated enum. */
    open fun setRegion(regionId: String) =
        prefs.edit { putString(KEY_AZURE_REGION, regionId) }

    /** Wipe both fields. The Settings "Forget key" button. */
    open fun clear() = prefs.edit {
        remove(KEY_AZURE_KEY)
        remove(KEY_AZURE_REGION)
    }

    /** True when a key is persisted. The Voice Library (PR-4) reads
     *  this to decide whether Azure rows in the picker activate or
     *  collapse to a "Configure Azure key →" CTA. */
    open val isConfigured: Boolean get() = key() != null

    /** Test-only constructor — bypasses SharedPreferences. Subclasses
     *  override the per-field getters/setters with whatever's
     *  convenient. Same shape as `LlmCredentialsStore.forTesting()`. */
    protected constructor() : this(NullSharedPreferences)

    companion object {
        /** Test-only factory — every read returns null and every write
         *  is a no-op. Use this when constructing components that
         *  consume [AzureCredentials] but don't exercise the storage
         *  layer (most engine-handle tests). */
        fun forTesting(): AzureCredentials = AzureCredentials(NullSharedPreferences)

        /** Encrypted-prefs key namespace. Stays stable across builds —
         *  changing either of these would orphan an existing user's
         *  configured key. */
        internal const val KEY_AZURE_KEY = "azure_speech:key"
        internal const val KEY_AZURE_REGION = "azure_speech:region"
    }
}

/** A SharedPreferences that does nothing — every method returns the
 *  default value or no-ops. Used by [AzureCredentials.forTesting]
 *  when the test subclasses and overrides the high-level API. Mirrors
 *  the same helper in `LlmCredentialsStore` so the two test stories
 *  read the same way; we keep an independent instance here so
 *  `:source-azure` doesn't reach into `:core-llm`'s internals. */
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
