package `in`.jphe.storyvox.source.radio

/**
 * Issue #417 — hand-curated seed list of radio stations rendered as
 * storyvox fictions out-of-the-box. The user adds long-tail entries
 * via Browse → Radio → Search → star (which hits Radio Browser's CC0
 * directory and persists the picked stations into
 * `pref_radio_starred_station_ids`; see [RadioSource]).
 *
 * ## Curation criteria
 *
 * Every entry must:
 * 1. Stream over HTTPS (Media3 default is cleartext-disallowed on API
 *    28+; we don't relax that for one station).
 * 2. Publish a stable stream URL — no per-session token, no JS-driven
 *    iHeart embed. URLs verified via `curl -sI` during PR author
 *    sweeps; broken entries trim down rather than ship gracefully.
 * 3. Sit in a category the long-form storyvox listener actually wants:
 *    local community radio (KVMR), public radio (KQED, KXPR), college
 *    radio (KCSB), and curated specialty (SomaFM). Random pop stations
 *    are the Radio Browser long-tail's job, not the seed list's.
 *
 * ## Trimmed during v0.5.32 curation pass
 *
 * - **KNCO 830 AM / 94.1 FM (Nevada County news/talk)** — listed in
 *   the issue's seed roster but their listen-live page uses an iHeart
 *   embed with no stable stream URL discoverable via curl; not present
 *   in the Radio Browser directory either. JP can star it via Radio
 *   Browser if/when it lands there. Re-add this constant when a stable
 *   stream URL surfaces.
 *
 * ## Migration posture
 *
 * The first entry's id is intentionally `"kvmr"` — this matches the
 * existing v0.5.20+ persisted `fictionId = "kvmr:live"` rows so a
 * user's Library / Follows / playback-position state survives the
 * :source-kvmr → :source-radio rename. The `kvmr` station id is the
 * key, not the now-renamed source id.
 */
internal object RadioStations {

    /**
     * The curated v0.5.32 seed list. Order is the order the user sees
     * on Browse → Radio → Popular (KVMR first because it's the
     * preserved-from-v0.5.20 entry; the rest sort by geographic and
     * editorial relevance for a NorCal-rooted user base).
     */
    val curated: List<RadioStation> = listOf(
        RadioStation(
            id = "kvmr",
            displayName = "KVMR 89.3 FM",
            description = "Music of the world. Voice of the community. " +
                "Live stream from KVMR community radio in Nevada City, California.",
            // Carried over from :source-kvmr (issue #374, v0.5.20). The
            // `#.mp3` fragment is an ExoPlayer-ignored hint for older
            // players that want a familiar extension — kept so the
            // persisted Chapter.audioUrl rows from v0.5.20 still match
            // byte-for-byte after migration.
            streamUrl = "https://sslstream.kvmr.org:9433/aac96#.mp3",
            streamCodec = "AAC",
            country = "United States",
            language = "English",
            tags = listOf("community radio", "live", "Nevada City"),
            homepage = "https://kvmr.org",
        ),
        RadioStation(
            id = "kxpr",
            displayName = "Capital Public Radio (KXPR)",
            description = "Sacramento public radio — classical, news, and " +
                "in-depth public radio programming from CapRadio.",
            // StreamTheWorld 302-redirects to a per-edge host; Media3's
            // OkHttpDataSource follows redirects transparently.
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/KXPR.mp3",
            streamCodec = "MP3",
            country = "United States",
            language = "English",
            tags = listOf("public radio", "classical", "news", "Sacramento"),
            homepage = "https://www.capradio.org",
        ),
        RadioStation(
            id = "kqed",
            displayName = "KQED 88.5 FM",
            description = "San Francisco Bay Area NPR member station — " +
                "news, talk, and cultural programming.",
            streamUrl = "https://streams.kqed.org/kqedradio",
            streamCodec = "MP3",
            country = "United States",
            language = "English",
            tags = listOf("public radio", "news", "talk", "Bay Area"),
            homepage = "https://www.kqed.org/radio",
        ),
        RadioStation(
            id = "kcsb",
            displayName = "KCSB 91.9 FM",
            description = "UC Santa Barbara college radio — freeform " +
                "music, news, and student-produced programming.",
            // Stream URL verified via Radio Browser
            // (stationuuid: 4af97ba5-4546-4132-a94a-ebca09214cfb)
            streamUrl = "https://kcsb.streamguys1.com/live",
            streamCodec = "MP3",
            country = "United States",
            language = "English",
            tags = listOf("college radio", "freeform", "Santa Barbara"),
            homepage = "https://www.kcsb.org",
        ),
        RadioStation(
            id = "somafm-groove-salad",
            displayName = "SomaFM Groove Salad",
            description = "A nicely chilled plate of ambient/downtempo " +
                "beats and grooves. Commercial-free internet radio from " +
                "SomaFM in San Francisco.",
            streamUrl = "https://ice2.somafm.com/groovesalad-128-mp3",
            streamCodec = "MP3",
            country = "United States",
            language = "English",
            tags = listOf("ambient", "downtempo", "commercial-free", "internet radio"),
            homepage = "https://somafm.com/groovesalad",
        ),
    )

    /**
     * Lookup helper — returns the curated station with matching [id],
     * or null. Used by [RadioSource.fictionDetail] to resolve a
     * fictionId like `"kvmr:live"` back to the descriptor without
     * iterating the list at every call site.
     */
    fun byId(id: String): RadioStation? = curated.firstOrNull { it.id == id }
}
