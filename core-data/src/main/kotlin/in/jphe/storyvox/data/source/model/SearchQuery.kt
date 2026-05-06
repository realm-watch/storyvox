package `in`.jphe.storyvox.data.source.model

/**
 * Free-form search input. Sources translate the relevant subset to whatever
 * advanced-search parameters they support; unsupported filters are silently
 * ignored.
 *
 * The full set mirrors Royal Road's `/fictions/search` form. Other sources
 * (when added) take whatever overlaps with their own search surface.
 */
data class SearchQuery(
    val term: String = "",
    /** Royal Road "Genres" — the 17 hard-coded primary genre tags (action, fantasy, …). */
    val genres: Set<String> = emptySet(),
    /** Additional tags (`tagsAdd`); story must match ALL of these. */
    val tags: Set<String> = emptySet(),
    /** Tags to exclude (`tagsRemove`); story must match NONE of these. */
    val excludeTags: Set<String> = emptySet(),
    val statuses: Set<FictionStatus> = emptySet(),
    /** Content warnings to require — author has self-flagged the story with these. */
    val requireWarnings: Set<ContentWarning> = emptySet(),
    /** Content warnings to exclude — story is flagged with any of these. */
    val excludeWarnings: Set<ContentWarning> = emptySet(),
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val minRating: Float? = null,
    val maxRating: Float? = null,
    val type: FictionType = FictionType.ALL,
    val orderBy: SearchOrder = SearchOrder.RELEVANCE,
    val direction: SortDirection = SortDirection.DESC,
    val page: Int = 1,
)

enum class SearchOrder { RELEVANCE, POPULARITY, RATING, LAST_UPDATE, RELEASE_DATE, FOLLOWERS, LENGTH, VIEWS, TITLE, AUTHOR }

enum class SortDirection { ASC, DESC }

enum class FictionType { ALL, ORIGINAL, FAN_FICTION }

/**
 * Content warnings as Royal Road labels them. Authors self-apply these when
 * publishing; the search form surfaces them as include/exclude filters.
 */
enum class ContentWarning {
    PROFANITY,
    SEXUAL_CONTENT,
    GRAPHIC_VIOLENCE,
    SENSITIVE_CONTENT,
    AI_ASSISTED,
    AI_GENERATED,
}
