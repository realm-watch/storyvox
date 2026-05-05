package `in`.jphe.storyvox.data.source.model

/**
 * Lightweight representation of a fiction used in lists (browse, search, follows).
 *
 * Sources may leave optional fields null when the listing page doesn't expose them;
 * the gap is filled later by [FictionDetail] from a detail-page fetch.
 */
data class FictionSummary(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val status: FictionStatus = FictionStatus.ONGOING,
    val chapterCount: Int? = null,
    val rating: Float? = null,
)
