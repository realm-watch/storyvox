package `in`.jphe.storyvox.data.source.model

/**
 * One page of a paginated list. `hasNext` lets the caller decide whether to
 * trigger a follow-up fetch without inferring page count from item totals.
 */
data class ListPage<T>(
    val items: List<T>,
    val page: Int,
    val hasNext: Boolean,
)
