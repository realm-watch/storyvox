package `in`.jphe.storyvox.data.source.model

/**
 * Free-form search input. Sources translate the relevant subset to whatever
 * advanced-search parameters they support; unsupported filters are silently
 * ignored (Royal Road for instance has rich genre/tag include/exclude lists).
 */
data class SearchQuery(
    val term: String,
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val excludeTags: Set<String> = emptySet(),
    val statuses: Set<FictionStatus> = emptySet(),
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val orderBy: SearchOrder = SearchOrder.RELEVANCE,
    val page: Int = 1,
)

enum class SearchOrder { RELEVANCE, POPULARITY, RATING, LAST_UPDATE, RELEASE_DATE, TITLE }
