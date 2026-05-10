package `in`.jphe.storyvox.source.azure

/**
 * Azure Speech resource region. Endpoints are region-scoped — the
 * subscription key only authorizes the region the resource was created
 * in, so picking the wrong region returns 401 even if the key is
 * valid. Settings → Sources → Azure (PR-3) surfaces this as a
 * dropdown.
 *
 * The full Azure region list is ~60 entries; we ship a curated subset
 * for v1 covering the regions JP's user base is most likely to be
 * close to. "Other" lets a power user with a resource in (say)
 * `westindia` paste the raw region id and we trust their input. The
 * picker entry is opt-in friction — no auto-detect from device locale
 * because the Azure resource lives wherever the user created it,
 * which is rarely a function of where the device lives.
 *
 * Region id format mirrors Azure's: lowercase, no spaces. Used
 * verbatim in [AzureSpeechClient]'s endpoint URL —
 * `https://{region}.tts.speech.microsoft.com/cognitiveservices/v1`.
 */
enum class AzureRegion(val id: String, val displayName: String) {
    /** US East — cheapest, most-feature-complete (Solara's recommended
     *  default). Paid tier, F0 free tier, and Dragon HD all available. */
    EastUs("eastus", "US East"),
    /** US West — Pacific-coast resources land here; same tier coverage
     *  as EastUs in practice. */
    WestUs("westus", "US West"),
    /** US West 2 — next-best US fallback if East is congested. */
    WestUs2("westus2", "US West 2"),
    /** West Europe — closest to most EU users; supports the same
     *  voice tiers as EastUs as of 2026-05. */
    WestEurope("westeurope", "West Europe"),
    /** East Asia — Tokyo / Singapore alternative for APAC users. */
    EastAsia("eastasia", "East Asia");

    companion object {
        /** The runtime default when no user choice has been persisted —
         *  matches Solara's open-question #3 recommendation. */
        val DEFAULT: AzureRegion = EastUs

        /** Resolve a region id (as stored in EncryptedSharedPreferences)
         *  to its enum entry. Returns null on unknown ids — the
         *  "Other (paste raw id)" Settings affordance is layered above
         *  this; pure lookup stays strict. */
        fun byId(id: String): AzureRegion? = entries.firstOrNull { it.id == id }
    }
}
