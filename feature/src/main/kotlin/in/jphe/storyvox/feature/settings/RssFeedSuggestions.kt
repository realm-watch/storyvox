package `in`.jphe.storyvox.feature.settings

/**
 * Curated suggested-feeds list (#236 follow-up). Surfaces in Settings →
 * Library & Sync → RSS as one-tap-add buttons. Hand-curated; verified
 * RSS-valid as of the date in [SuggestedFeed.verifiedOn] field.
 *
 * Categorization:
 *  - **Text articles** narrate cleanly via TTS — full body text in
 *    `<content:encoded>`, suitable for listening.
 *  - **Audio podcasts** have audio enclosures but only show-notes
 *    text; storyvox narrates the show-notes (not the original audio).
 *    Still useful for *browsing* what's been released.
 *
 * Adding a feed: append to [SUGGESTED_FEEDS]. The data class fields
 * are stable; [SuggestedFeed.kind] drives the visual treatment.
 */
data class SuggestedFeed(
    val title: String,
    val description: String,
    val url: String,
    val category: String,
    val kind: SuggestedFeedKind,
)

enum class SuggestedFeedKind {
    /** Text-article feed — narrates well via TTS. */
    Text,

    /** Audio-podcast feed — show-notes narrate, audio enclosure
     *  doesn't (storyvox doesn't currently stream feed audio). */
    AudioPodcast,
}

/**
 * Hand-curated, verified-valid RSS feeds. Grouped by [category] for
 * the picker UI. Order within a category matters (most-recommended
 * first).
 */
val SUGGESTED_FEEDS: List<SuggestedFeed> = listOf(
    // ── Buddhist & dharma ────────────────────────────────────────
    SuggestedFeed(
        title = "Tricycle: The Buddhist Review",
        description = "Long-form essays, philosophy, book reviews. Daily-ish.",
        url = "https://tricycle.org/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Lion's Roar",
        description = "Contemporary Buddhist wisdom, mindfulness + practice.",
        url = "https://www.lionsroar.com/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Buddhistdoor Global",
        description = "Asian Buddhist news + cultural analysis.",
        url = "https://www.buddhistdoor.net/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Dharma Seed",
        description = "Theravada vipassana + metta talks (Spirit Rock + IMS).",
        url = "https://dharmaseed.org/feeds/recordings/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "AudioDharma",
        description = "Insight Meditation Center dharma talks (Gil Fronsdal).",
        url = "https://feeds.feedburner.com/audiodharma",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "Plum Village dharma talks",
        description = "Thich Nhat Hanh tradition.",
        url = "https://plumvillage.org/feed/audio/dharma-talks/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "Plum Village — The Way Out Is In",
        description = "Brother Phap Huu + Jo Confino podcast series.",
        url = "https://plumvillage.org/feed/audio/the-way-out-is-in/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
)
