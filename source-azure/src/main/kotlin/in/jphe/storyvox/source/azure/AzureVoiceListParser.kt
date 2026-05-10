package `in`.jphe.storyvox.source.azure

import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceTier
import org.json.JSONArray
import org.json.JSONException

/**
 * Parses Azure's `voices/list` JSON payload into the lean
 * [AzureVoiceDescriptor] shape that `:core-playback` consumes.
 *
 * Uses `org.json` (already on the classpath via the Android SDK) rather
 * than kotlinx.serialization because the Azure schema carries optional
 * fields (`VoiceTag.VoicePersonalities`, `WordsPerMinute`, `RolePlayList`)
 * we don't need and the schema drifts as Azure adds tiers — a strict
 * deserializer would refuse new top-level fields and break on every
 * Azure release. `org.json`'s "fish out the keys you care about, ignore
 * the rest" model survives that drift cleanly.
 */
internal object AzureVoiceListParser {

    /**
     * Parse the full body. Returns an empty list on parse failure
     * rather than throwing — the caller treats "couldn't parse the
     * roster" the same as "no key configured": surface an empty
     * picker section, not a crash. Logs the failure cause via the
     * existing `Log.w` taxonomy in the caller.
     *
     * Filters voices the picker can't act on today: any locale that
     * isn't supported, missing required fields. Does NOT pre-filter
     * by language — the caller handles that, since "show only
     * en-US" is a UI policy, not a data-layer concern.
     */
    fun parse(body: String): List<AzureVoiceDescriptor> {
        if (body.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<AzureVoiceDescriptor>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val shortName = obj.optString("ShortName").takeIf { it.isNotBlank() }
                    ?: continue
                val displayName = obj.optString("DisplayName").takeIf { it.isNotBlank() }
                    ?: shortName
                val locale = obj.optString("Locale").takeIf { it.isNotBlank() }
                    ?: continue
                val gender = obj.optString("Gender").ifBlank { "Neutral" }
                out += AzureVoiceDescriptor(
                    shortName = shortName,
                    displayName = displayName,
                    locale = locale,
                    gender = gender,
                    tier = AzureVoiceTier.classify(shortName),
                )
            }
            out
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
