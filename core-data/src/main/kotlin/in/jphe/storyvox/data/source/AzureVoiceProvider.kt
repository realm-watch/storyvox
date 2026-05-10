package `in`.jphe.storyvox.data.source

import kotlinx.coroutines.flow.Flow

/**
 * Live roster of Azure Speech voices available in the user's configured
 * region. Populated by `:source-azure` (which fetches the
 * `voices/list` endpoint and parses it); consumed by `:core-playback`
 * (which projects the descriptors into [CatalogEntry] rows). The
 * interface lives here in `:core-data` so neither side needs a build
 * dependency on the other — see the cycle-avoidance note in
 * `:source-azure`'s `build.gradle.kts`.
 */
interface AzureVoiceProvider {
    /**
     * Live roster — empty until the first fetch completes (or when the
     * key is absent and the fetch is short-circuited). Updates whenever
     * the region or key changes; consumers should treat this as a hot
     * Flow and expose it directly to the UI rather than caching a
     * snapshot.
     */
    val voices: Flow<List<AzureVoiceDescriptor>>

    /**
     * Re-fetch the roster from Azure. Called automatically on region /
     * key changes; Settings → Cloud Voices → Refresh exposes it
     * manually. Idempotent — concurrent calls coalesce.
     */
    suspend fun refresh()
}

/**
 * One Azure Speech voice as surfaced by the live roster. Mirrors the
 * subset of `voices/list`'s JSON payload we need to render a picker
 * row; everything else (sample rate, voice tag personalities, etc.)
 * stays unparsed because the picker has no UI for it today.
 *
 * The `tier` carries our derived classification — Azure's response
 * doesn't have a single field for it, so we infer from `ShortName`
 * patterns ("DragonHD" → Dragon HD top tier, "Multilingual" or
 * "*Neural" → HD Neural). The classifier is in [AzureVoiceTier.classify].
 */
data class AzureVoiceDescriptor(
    /** Azure's `ShortName`, e.g. `"en-US-AriaNeural"` or
     *  `"en-US-Ava:DragonHDLatestNeural"`. Surfaced verbatim in the SSML
     *  `<voice name=...>` attribute, so the colon form (where present)
     *  must be preserved. */
    val shortName: String,
    /** Azure's `DisplayName`, e.g. `"Aria"` or `"Ava"`. Used as the
     *  human-facing label; the catalog row composes
     *  `"☁️ {displayName} · {locale} · {tier}"`. */
    val displayName: String,
    /** BCP-47 locale, e.g. `"en-US"`. The catalog uses underscored
     *  form (`"en_US"`); convert at the boundary. */
    val locale: String,
    /** `"Female"` / `"Male"` / `"Neutral"` per Azure's schema. Used by
     *  future filter UI; not surfaced in the picker today. */
    val gender: String,
    /** Derived tier — see [AzureVoiceTier]. */
    val tier: AzureVoiceTier,
)

/**
 * Voice tier classification — Azure's response splits this across
 * multiple fields (ShortName patterns, VoiceTag.ModelSeries) so we
 * collapse to one enum here. Drives the picker's tier groups and the
 * cost-disclosure copy ("Dragon HD voices bill at $30/1M chars").
 */
enum class AzureVoiceTier(val displayLabel: String) {
    /** Azure's 2025 generative tier — `*:DragonHDLatestNeural`,
     *  `*:DragonHDOmniLatestNeural`, `*:DragonHDFlashLatestNeural`.
     *  Currently eastus-only; westus / westus2 carry zero of these. */
    DragonHd("Dragon HD"),
    /** HD-quality multilingual neural — `*MultilingualNeural`. */
    HdMultilingual("HD Multilingual"),
    /** Standard neural — `*Neural` without the Multilingual /
     *  DragonHD markers. The vast majority of voices land here. */
    Neural("Neural"),
    ;

    companion object {
        /** Classify by ShortName pattern. Order-sensitive — DragonHD
         *  before MultilingualNeural before plain Neural. */
        fun classify(shortName: String): AzureVoiceTier = when {
            "DragonHD" in shortName -> DragonHd
            "MultilingualNeural" in shortName -> HdMultilingual
            else -> Neural
        }
    }
}
