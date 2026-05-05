package `in`.jphe.storyvox.data.source.model

/**
 * Fully-populated fiction record returned by [FictionSource.fictionDetail].
 *
 * `chapters` is the full table-of-contents at fetch time — bodies are NOT included,
 * the caller must fetch each chapter separately (typically through a repository
 * which schedules `ChapterDownloadWorker`).
 */
data class FictionDetail(
    val summary: FictionSummary,
    val chapters: List<ChapterInfo>,
    val genres: List<String> = emptyList(),
    val wordCount: Long? = null,
    val views: Long? = null,
    val followers: Int? = null,
    val lastUpdatedAt: Long? = null,
    val authorId: String? = null,
)
